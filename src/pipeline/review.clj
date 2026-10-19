(ns pipeline.review
  "Review-silmukka. Yksi `review-round` = katselmoijat A ja B → (fixer → push).
  Actionsissa yksi ajo tekee yhden kierroksen; fixerin push käynnistää seuraavan.
  Kierrosnumero luetaan PR:n katselmoinneista, joten ajo on tilaton."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pipeline.agents :as agents]
            [pipeline.events :as events]
            [pipeline.git :as git]
            [pipeline.github :as gh]
            [pipeline.implement :as implement]
            [pipeline.prompts :as prompts]
            [pipeline.shell :as shell]))

;; --- Skeemat ---------------------------------------------------------------

(def finding-schema
  {"type" "object"
   "required" ["severity" "title" "body"]
   "properties" {"severity" {"type" "string" "enum" ["blocking" "suggestion" "nit"]}
                 "file" {"type" "string"}
                 "line" {"type" "integer"}
                 "title" {"type" "string"}
                 "body" {"type" "string"}}})

(def reviewer-a-schema
  {"type" "object"
   "required" ["findings"]
   "properties" {"findings" {"type" "array" "items" finding-schema}}})

(def reviewer-b-schema
  {"type" "object"
   "required" ["findings" "disputes"]
   "properties" {"findings" {"type" "array" "items" finding-schema}
                 "disputes" {"type" "array"
                             "items" {"type" "object"
                                      "required" ["id" "reason"]
                                      "properties" {"id" {"type" "string"}
                                                    "reason" {"type" "string"}}}}}})

(def fixer-schema
  {"type" "object"
   "required" ["responses"]
   "properties" {"summary" {"type" "string"}
                 "responses" {"type" "array"
                              "items" {"type" "object"
                                       "required" ["id" "status" "note"]
                                       "properties" {"id" {"type" "string"}
                                                     "status" {"type" "string" "enum" ["fixed" "declined"]}
                                                     "note" {"type" "string"}}}}}})

;; --- Puhtaat apufunktiot ----------------------------------------------------

(def round-re #"<!-- linjasto:round=(\d+) -->")

(defn last-round
  "Suurin kierrosnumero katselmointien teksteistä, 0 jos ei yhtään."
  [bodies]
  (reduce max 0 (keep #(some->> % (re-find round-re) second parse-long) bodies)))

(defn number-findings
  "Antaa löydöksille tunnisteet A1, A2… / B1, B2…"
  [prefix findings]
  (vec (map-indexed (fn [i f] (assoc f :id (str prefix (inc i)))) findings)))

(defn blocking-findings
  "A:n blokkaavat, joita B ei kiistänyt, sekä B:n omat blokkaavat."
  [findings-a findings-b disputes]
  (let [disputed (set (map :id disputes))
        blocking? #(= "blocking" (:severity %))]
    (vec (concat (filter #(and (blocking? %) (not (disputed (:id %)))) findings-a)
                 (filter blocking? findings-b)))))

(defn- loc [{:keys [file line]}]
  (cond (and file line) (str "`" file ":" line "` ")
        file (str "`" file "` ")
        :else ""))

(defn- finding-line [f]
  (str "- **" (:id f) "** " (loc f) (:title f) "\n  " (str/replace (str (:body f)) "\n" "\n  ")))

(defn review-body [{:keys [round max-rounds findings blocking disputes]}]
  (let [blocking-ids (set (map :id blocking))
        others (remove #(blocking-ids (:id %)) findings)
        disputed (set (map :id disputes))]
    (str "<!-- linjasto:round=" round " -->\n"
         "### Linjaston katselmointi, kierros " round "/" max-rounds "\n\n"
         (if (seq blocking)
           (str "**Blokkaavat (" (count blocking) ")**, fixer korjaa nämä:\n\n"
                (str/join "\n" (map finding-line blocking)) "\n\n")
           "✅ Ei blokkaavia löydöksiä.\n\n")
         (when (seq disputes)
           (str "**Reviewer B kiisti**\n\n"
                (str/join "\n" (map #(str "- **" (:id %) "**: " (:reason %)) disputes)) "\n\n"))
         (when (seq others)
           (str "<details><summary>Muut huomiot (" (count others) ")</summary>\n\n"
                (str/join "\n" (map #(str (finding-line %)
                                          (when (disputed (:id %)) " _(kiistetty)_"))
                                    others))
                "\n\n</details>\n")))))

(defn inline-comments [findings]
  (vec (for [f findings
             :when (and (:file f) (:line f))]
         {:path (:file f)
          :line (:line f)
          :side "RIGHT"
          :body (str "**" (:id f) " · " (:severity f) "** " (:title f) "\n\n" (:body f))})))

(defn fixer-comment [round {:keys [summary responses]}]
  (str "### Fixer, kierros " round "\n\n"
       (when-not (str/blank? summary) (str summary "\n\n"))
       (str/join "\n" (for [{:keys [id status note]} responses]
                        (str "- **" id "** "
                             (if (= "fixed" status) "✅ korjattu" "↩️ ei muutettu")
                             ": " note)))))

;; --- Vaiheet ----------------------------------------------------------------

(defn- ensure-checkout!
  "Palauttaa hakemiston, jossa PR:n haara on checkoutattuna.
  Actionsissa projektihakemisto on jo PR:n haarassa ja sitä käytetään sellaisenaan.
  Paikallisesti käyttäjän oma checkout jätetään rauhaan ja työ tehdään worktreessä."
  [{:keys [cfg project-dir]} issue-n head]
  (let [remote (:remote cfg)]
    (git/git! project-dir "fetch" remote head)
    (if (= head (git/current-branch project-dir))
      (do (git/git! project-dir "reset" "--hard" (str remote "/" head))
          project-dir)
      (let [wt (implement/worktree-path cfg project-dir (or issue-n (str "pr-" head)))]
        (if (fs/exists? wt)
          (git/git! wt "reset" "--hard" (str remote "/" head))
          (do (fs/create-dirs (fs/parent wt))
              (git/git! project-dir "worktree" "add" "-B" head wt (str remote "/" head))))
        wt))))

(defn- stop! [{:keys [cfg repo]} pr-n issue-n reason]
  (events/emit! :error {:msg (str "pysähtyi: " (first (str/split-lines reason)))})
  (let [labels (:labels cfg)]
    (doseq [n (remove nil? [pr-n issue-n])]
      (gh/set-labels! repo n {:remove [(:review labels)] :add [(:needs-human labels)]}))
    (gh/comment! repo pr-n (str "🛑 Linjasto pysähtyi: " reason))
    {:status :needs-human :pr pr-n :reason reason}))

(defn- merge! [{:keys [cfg repo]} pr-n issue-n]
  (let [labels (:labels cfg)
        how (gh/merge-pr! repo pr-n (:merge cfg))]
    (doseq [n (remove nil? [pr-n issue-n])]
      (gh/set-labels! repo n {:remove [(:review labels)] :add [(:merging labels)]}))
    (events/phase! (if (= how :auto) "auto-merge päällä, odottaa CI:tä" "PR mergetty"))
    {:status :clean :pr pr-n :merge how}))

(declare review-round*)

(defn review-round
  "Yksi katselmointikierros PR:lle. Palauttaa {:status :clean|:fixed|:needs-human|:skipped}."
  [ctx pr-n]
  (binding [events/*ctx* (assoc events/*ctx* :pr pr-n)]
    (review-round* ctx pr-n)))

(defn- review-round*
  [{:keys [cfg project-dir repo] :as ctx} pr-n]
  (let [labels (:labels cfg)
        pr (gh/pr repo pr-n)
        head (:headRefName pr)
        issue-n (implement/issue-number-from-branch cfg head)
        round (inc (last-round (gh/review-bodies repo pr-n)))
        max-rounds (:max-rounds cfg)]
    (binding [events/*ctx* (cond-> events/*ctx* issue-n (assoc :issue issue-n))]
    (cond
      (not= "OPEN" (:state pr))
      {:status :skipped :reason "PR ei ole auki"}

      (not ((gh/label-names pr) (:review labels)))
      {:status :skipped :reason (str "PR:llä ei ole labelia " (:review labels))}

      (> round max-rounds)
      (stop! ctx pr-n issue-n (str "blokkaavia löydöksiä on yhä " max-rounds
                                   " kierroksen jälkeen. Ihmisen vuoro."))

      :else
      (try
        (events/phase! (str "katselmointikierros " round "/" max-rounds) :round round)
        (let [dir (ensure-checkout! ctx issue-n head)
              issue (when issue-n (gh/issue repo issue-n))
              diff (shell/tail (gh/pr-diff repo pr-n) (:max-diff-chars cfg))
              base-vars {:pr-number pr-n
                         :issue-number (or issue-n "")
                         :issue-title (or (:title issue) (:title pr))
                         :issue-body (or (:body issue) "")
                         :base-branch (:base-branch cfg)
                         :remote (:remote cfg)
                         :diff diff
                         :round round
                         :language (:language cfg)}
              role-a (get-in cfg [:roles :reviewer-a])
              role-b (get-in cfg [:roles :reviewer-b])
              ;; Reviewer A
              res-a (agents/run-agent role-a {:role :reviewer-a :dir dir :schema reviewer-a-schema
                                              :prompt (prompts/prompt project-dir "reviewer-a"
                                                                      (assoc base-vars :focus (:focus role-a)))})
              findings-a (number-findings "A" (get-in res-a [:data :findings]))
              ;; Reviewer B näkee A:n löydökset ja voi kiistää ne
              res-b (agents/run-agent role-b {:role :reviewer-b :dir dir :schema reviewer-b-schema
                                              :prompt (prompts/prompt project-dir "reviewer-b"
                                                                      (assoc base-vars
                                                                             :focus (:focus role-b)
                                                                             :findings-a (json/generate-string findings-a {:pretty true})))})
              _ (when (or (nil? (:data res-a)) (nil? (:data res-b)))
                  (throw (ex-info "Katselmoijan vastausta ei voitu jäsentää JSONiksi." {})))
              findings-b (number-findings "B" (get-in res-b [:data :findings]))
              disputes (vec (get-in res-b [:data :disputes]))
              all (into findings-a findings-b)
              blocking (blocking-findings findings-a findings-b disputes)]
          (gh/create-review! repo pr-n {:body (review-body {:round round :max-rounds max-rounds
                                                            :findings all :blocking blocking
                                                            :disputes disputes})
                                        :comments (inline-comments all)})
          (events/phase! (str "löydöksiä " (count all) ", blokkaavia " (count blocking)
                              (when (seq disputes) (str ", kiistettyjä " (count disputes))))
                         :findings (count all) :blocking (count blocking))
          (if (empty? blocking)
            (merge! ctx pr-n issue-n)
            ;; Fixer
            (let [res-f (agents/run-agent (get-in cfg [:roles :fixer])
                                          {:role :fixer :dir dir :schema fixer-schema
                                           :prompt (prompts/prompt project-dir "fixer"
                                                                   (assoc base-vars :blocking
                                                                          (json/generate-string blocking {:pretty true})))})
                  changed? (git/commit-all! dir (str "#" (or issue-n pr-n) ": katselmoinnin korjaukset, kierros " round))]
              (gh/comment! repo pr-n (fixer-comment round (or (:data res-f)
                                                              {:summary (shell/tail (:text res-f) 2000)})))
              (if changed?
                (do (events/phase! "pushataan korjaukset")
                    (let [bad (git/protected-violations
                               (git/changed-files dir (:remote cfg) (:base-branch cfg))
                               (:protected-paths cfg))]
                      (when (seq bad)
                        (throw (ex-info (str "Fixer muutti suojattuja polkuja: " (str/join ", " bad)) {}))))
                    (git/push! dir (:remote cfg) head)
                    {:status :fixed :pr pr-n :round round})
                (stop! ctx pr-n issue-n "fixer ei tehnyt muutoksia blokkaaviin löydöksiin.")))))
        (catch Exception e
          (stop! ctx pr-n issue-n (str "virhe kierroksella " round ":\n\n```\n"
                                       (shell/tail (ex-message e) 3000) "\n```"))))))))

(defn review-loop
  "Paikallinen ajo: kierroksia kunnes PR on puhdas tai pysähtyy.
  Actionsissa tätä ei tarvita, koska fixerin push käynnistää seuraavan ajon."
  [ctx pr-n]
  (loop []
    (let [res (review-round ctx pr-n)]
      (if (= :fixed (:status res)) (recur) res))))

(defn mark-merged
  "PR mergettiin developiin: issue saa labelin agent:in-develop."
  [{:keys [cfg repo]} pr-n]
  (let [labels (:labels cfg)
        pr (gh/pr repo pr-n)
        issue-n (implement/issue-number-from-branch cfg (:headRefName pr))]
    (doseq [n (remove nil? [pr-n issue-n])]
      (gh/set-labels! repo n {:remove [(:merging labels) (:review labels)]
                              :add [(:in-develop labels)]}))
    (when issue-n
      (gh/comment! repo issue-n (str "✅ Toteutus on mergetty haaraan `" (:baseRefName pr)
                                     "` (PR #" pr-n "). Sulje issue, kun muutos on viety mainiin.")))
    {:status :marked :pr pr-n :issue issue-n}))
