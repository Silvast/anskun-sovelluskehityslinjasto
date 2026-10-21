(ns pipeline.release
  "Release-PR develop → main ja julkaisu.

  1. Linjasto pitää auki yhtä release-PR:ää. Jokaisesta developiin mergetystä
     issuesta kuvaukseen lisätään rivi `- Closes #n`, joten GitHub sulkee issuet,
     kun release-PR mergetään mainiin.
  2. Agentti kirjoittaa kuvaukseen release notesit ja ehdottaa versionostoa.
     Jos muokkaat notesia käsin, linjasto ei enää kirjoita niitä yli.
  3. Kun release-PR on mergetty, `publish!` nostaa version, päivittää
     CHANGELOGin, tekee tagin ja GitHub Releasen ja yhdistää mainin takaisin developiin."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pipeline.agents :as agents]
            [pipeline.config :as config]
            [pipeline.events :as events]
            [pipeline.git :as git]
            [pipeline.github :as gh]
            [pipeline.prompts :as prompts]
            [pipeline.shell :as shell])
  (:import (java.security MessageDigest)
           (java.time LocalDate)))

(def marker "<!-- linjasto:release -->")
(def notes-end "<!-- /linjasto:notes -->")
(def notes-re #"(?s)<!-- linjasto:notes([^>]*)-->\n(.*?)\n<!-- /linjasto:notes -->")

(defn initial-body [base-branch main-branch]
  (str "Collects the changes merged into `" base-branch "` for `" main-branch "`.\n"
       "The pipeline updates this description automatically. Merging this PR closes the issues listed below.\n\n"
       marker "\n"))

(defn release-line [{:keys [issue pr title]}]
  (str "- Closes #" issue
       (when pr (str " · PR #" pr))
       (when-not (str/blank? title) (str " · " title))))

(defn includes-issue? [body issue-n]
  (boolean (re-find (re-pattern (str "(?m)^- Closes #" issue-n "\\b")) (or body ""))))

(defn add-line
  "Lisää rivin kuvauksen loppuun, ellei issue ole jo listalla."
  [body entry]
  (let [body (if (str/includes? (or body "") marker) body (str body "\n\n" marker "\n"))]
    (if (includes-issue? body (:issue entry))
      body
      (str (str/trimr body) "\n" (release-line entry) "\n"))))

(defn listed-items
  "Kuvauksen Closes-rivit: [{:issue 13 :pr 14 :title \"…\"}]."
  [body]
  (vec (for [[_ issue pr title] (re-seq #"(?m)^- Closes #(\d+)(?: · PR #(\d+))?(?: · (.*))?$" (or body ""))]
         {:issue (parse-long issue)
          :pr (some-> pr parse-long)
          :title (some-> title str/trim)})))

;; --- Versiot ------------------------------------------------------------------

(defn parse-version [s]
  (when-let [[_ a b c] (re-find #"(\d+)\.(\d+)\.(\d+)" (or s ""))]
    (mapv parse-long [a b c])))

(defn version-str [[a b c]] (str a "." b "." c))

(defn bump-version [v bump]
  (let [[a b c] (or v [0 0 0])]
    (case bump
      "major" [(inc a) 0 0]
      "minor" [a (inc b) 0]
      [a b (inc c)])))

(defn max-version [vs]
  (last (sort (remove nil? vs))))

(defn current-version
  "Suurin versio mainin versiotiedostosta ja olemassa olevista tageista."
  [{:keys [cfg project-dir]}]
  (let [{:keys [main-branch version-file tag-prefix]} (:release cfg)
        remote (:remote cfg)]
    (git/git project-dir "fetch" "--tags" remote main-branch)
    (max-version
     (concat
      [(when version-file
         (let [{:keys [exit out]} (git/git project-dir "show" (str remote "/" main-branch ":" version-file))]
           (when (zero? exit)
             (parse-version (:version (json/parse-string out true))))))]
      (map parse-version
           (str/split-lines (:out (git/git project-dir "tag" "--list" (str tag-prefix "*")))))))))

;; --- Release notes --------------------------------------------------------------

(defn- sha1 [s]
  (let [d (.digest (MessageDigest/getInstance "SHA-1") (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) d)) 0 12)))

(defn notes-block
  "Kuvauksen notes-osio: {:attrs {\"bump\" \"minor\" …} :text \"…\"} tai nil."
  [body]
  (when-let [[_ attrs text] (re-find notes-re (or body ""))]
    {:attrs (into {} (map (fn [[_ k v]] [k v]) (re-seq #"(\w+)=(\S+)" attrs)))
     :text text}))

(defn hand-edited?
  "Onko notes-osiota muokattu käsin linjaston kirjoittamisen jälkeen."
  [body]
  (when-let [{:keys [attrs text]} (notes-block body)]
    (not= (get attrs "sha") (sha1 text))))

(defn with-notes
  "Korvaa tai lisää notes-osion ennen Closes-listaa."
  [body {:keys [version bump notes]}]
  (let [text (str "## Release notes (v" version ")\n\n" (str/trim notes))
        block (str "<!-- linjasto:notes bump=" bump " version=" version " sha=" (sha1 text) " -->\n"
                   text "\n" notes-end)]
    (if (re-find notes-re body)
      (str/replace body notes-re (str/re-quote-replacement block))
      (str/replace-first body marker (str/re-quote-replacement (str block "\n\n" marker))))))

(defn notes-text
  "Julkaistava notes-teksti ilman otsikkoa, tai varalla lista issueista."
  [body]
  (if-let [{:keys [text]} (notes-block body)]
    (str/trim (str/replace-first text #"^## Release notes[^\n]*\n" ""))
    (str/join "\n" (for [{:keys [issue title]} (listed-items body)]
                     (str "- " (or title (str "#" issue)))))))

(defn- change-summary [repo {:keys [issue pr]}]
  (let [i (gh/issue repo issue)
        p (when pr (gh/pr repo pr))]
    (str "### " (:title i) "\n\n"
         "Issue:\n" (shell/tail (or (:body i) "") 1500) "\n\n"
         (when p (str "Implementation summary:\n" (shell/tail (or (:body p) "") 1500) "\n")))))

(defn refresh-notes!
  "Kirjoittaa release-PR:n notesit uudelleen agentilla. Ei koske käsin muokattuihin."
  [{:keys [cfg repo project-dir] :as ctx} pr-n]
  (when (get-in cfg [:release :notes])
    (let [body (:body (gh/pr repo pr-n))]
      (if (hand-edited? body)
        (events/phase! (str "release-PR #" pr-n ": notesia on muokattu käsin, ei kirjoiteta yli"))
        (try
          (events/phase! (str "release-PR #" pr-n ": kirjoitetaan release notes"))
          (let [items (listed-items body)
                res (agents/run-agent
                     (get-in cfg [:roles :release-notes])
                     {:role :release-notes :dir project-dir
                      :schema {"type" "object" "required" ["bump" "notes"]
                               "properties" {"bump" {"type" "string" "enum" ["minor" "patch"]}
                                             "notes" {"type" "string"}}}
                      :prompt (prompts/prompt project-dir "release-notes"
                                              {:base-branch (:base-branch cfg)
                                               :main-branch (get-in cfg [:release :main-branch])
                                               :changes (str/join "\n\n" (map #(change-summary repo %) items))})})
                {:keys [bump notes]} (:data res)
                _ (when (str/blank? notes) (throw (ex-info "Agent returned no release notes." {})))
                version (version-str (bump-version (current-version ctx) bump))]
            (gh/edit-pr-body! repo pr-n (with-notes body {:version version :bump bump :notes notes})))
          (catch Exception e
            (shell/log "Release notesia ei voitu kirjoittaa:" (ex-message e))))))))

;; --- Release-PR --------------------------------------------------------------------

(defn ensure-release-pr!
  "Luo release-PR:n, jos sitä ei ole, lisää siihen issuen ja päivittää notesit.
  Palauttaa PR:n numeron tai nil, jos PR:ää ei voitu luoda."
  [{:keys [cfg repo] :as ctx} entry]
  (let [{:keys [main-branch title]} (:release cfg)
        base (:base-branch cfg)
        existing (gh/open-pr-between repo base main-branch)
        n (if existing
            (let [body (add-line (:body existing) entry)]
              (when-not (= body (:body existing))
                (gh/edit-pr-body! repo (:number existing) body)
                (events/phase! (str "release-PR #" (:number existing) ": lisätty issue #" (:issue entry))))
              (:number existing))
            (try
              (let [n (gh/create-pr! repo {:base main-branch :head base :title title
                                           :body (add-line (initial-body base main-branch) entry)})]
                (events/phase! (str "release-PR #" n " luotu (" base " → " main-branch ")"))
                n)
              (catch Exception e
                (shell/log "Release-PR:ää ei voitu luoda:" (ex-message e))
                nil)))]
    (when n (refresh-notes! ctx n))
    n))

;; --- Julkaisu ------------------------------------------------------------------------

(defn set-json-version
  "Vaihtaa JSON-tiedoston ylimmän tason \"version\"-arvon säilyttäen muotoilun.
  package-lock.jsonissa sama arvo on myös packages[\"\"]-kohdassa, joten
  korvataan kaksi ensimmäistä osumaa, jotka ovat vanha versio."
  [text old new]
  (let [pat (re-pattern (str "\"version\"(\\s*):(\\s*)\"" (java.util.regex.Pattern/quote old) "\""))
        n (atom 0)]
    (str/replace text pat (fn [[m s1 s2]]
                            (if (< (swap! n inc) 3)
                              (str "\"version\"" s1 ":" s2 "\"" new "\"")
                              m)))))

(defn prepend-changelog [existing version date notes]
  (let [entry (str "## v" version " – " date "\n\n" (str/trim notes) "\n")]
    (if (and existing (str/starts-with? (str/trim existing) "# Changelog"))
      (str/replace-first existing #"# Changelog\s*\n" (str/re-quote-replacement (str "# Changelog\n\n" entry "\n")))
      (str "# Changelog\n\n" entry (when existing (str "\n" existing))))))

(defn- release-commit!
  "Nostaa version ja päivittää CHANGELOGin mainiin. Palauttaa true, jos push onnistui."
  [{:keys [cfg]} wt old new notes]
  (let [{:keys [version-file changelog main-branch]} (:release cfg)
        remote (:remote cfg)]
    (when version-file
      (doseq [f [version-file (when (= "package.json" version-file) "package-lock.json")]
              :when (and f (fs/exists? (fs/path wt f)))]
        (let [p (str (fs/path wt f))]
          (spit p (set-json-version (slurp p) old new)))))
    (when changelog
      (let [p (str (fs/path wt changelog))]
        (spit p (prepend-changelog (when (fs/exists? p) (slurp p)) new (str (LocalDate/now)) notes))))
    (if (git/commit-all! wt (str "Release v" new))
      (let [{:keys [exit err]} (git/git wt "push" remote (str "HEAD:" main-branch))]
        (if (zero? exit)
          true
          (do (shell/log "Mainiin ei voitu pushata (branch protection?), tagataan ilman versionostoa:" (str/trim (str err)))
              (git/git! wt "reset" "--hard" (str remote "/" main-branch))
              false)))
      false)))

(defn- sync-develop!
  "Yhdistää mainin takaisin developiin, jotta versionosto ja release-merge ovat molemmissa."
  [{:keys [cfg]} wt]
  (let [{:keys [main-branch]} (:release cfg)
        remote (:remote cfg)
        base (:base-branch cfg)]
    (git/git! wt "fetch" remote main-branch base)
    (git/git! wt "checkout" "-B" "linjasto-sync" (str remote "/" base))
    (let [m (git/git wt "merge" "--no-edit" (str remote "/" main-branch))]
      (if-not (zero? (:exit m))
        (do (git/git wt "merge" "--abort")
            "mainin yhdistäminen developiin epäonnistui konfliktiin")
        (let [{:keys [exit err]} (git/git wt "push" remote (str "HEAD:" base))]
          (when-not (zero? exit)
            (str "developiin ei voitu pushata: " (str/trim (str err)))))))))

(defn publish!
  "Julkaisee mergetyn release-PR:n: versio, CHANGELOG, tagi, GitHub Release, develop-synkka."
  [{:keys [cfg repo project-dir] :as ctx} {:keys [number body]}]
  (binding [events/*ctx* (assoc events/*ctx* :pr number)]
    (let [{:keys [main-branch tag-prefix]} (:release cfg)
          remote (:remote cfg)
          labels (:labels cfg)
          bump (get-in (notes-block body) [:attrs "bump"] "patch")
          old (current-version ctx)
          new (version-str (bump-version old bump))
          tag (str tag-prefix new)
          notes (notes-text body)
          wt (str (fs/path (config/worktree-root cfg project-dir) "release"))]
      (events/phase! (str "julkaistaan " tag " (release-PR #" number ")"))
      (git/remove-worktree! project-dir wt)
      (git/git! project-dir "fetch" remote main-branch)
      (fs/create-dirs (fs/parent wt))
      (git/git! project-dir "worktree" "add" "-B" "linjasto-release" wt (str remote "/" main-branch))
      (try
        (let [bumped? (when old (release-commit! ctx wt (version-str old) new notes))]
          (when-not (zero? (:exit (git/git wt "rev-parse" "-q" "--verify" (str "refs/tags/" tag))))
            (git/git! wt "tag" "-a" tag "-m" (str "Release " tag))
            (git/git! wt "push" remote tag))
          (when-not (zero? (:exit (shell/sh ["gh" "release" "view" tag "--repo" repo])))
            (shell/sh! ["gh" "release" "create" tag "--repo" repo "--title" tag
                        "--notes-file" "-" "--verify-tag"]
                       {:in notes}))
          (let [sync-problem (sync-develop! ctx wt)
                url (str/trim (shell/sh! ["gh" "release" "view" tag "--repo" repo "--json" "url" "-q" ".url"]))]
            (gh/add-labels! repo number [(:released labels)])
            (gh/comment! repo number
                         (str "🚀 Released " tag ": " url
                              (when-not bumped? "\n\nThe version file was not updated (no version found or the push to main was rejected).")
                              (when sync-problem (str "\n\n⚠️ Could not sync `" main-branch "` back into `"
                                                      (:base-branch cfg) "`: " sync-problem ". Merge it by hand."))))
            (events/phase! (str tag " julkaistu" (when sync-problem (str "; " sync-problem))))
            {:status :released :tag tag :url url}))
        (finally
          (git/remove-worktree! project-dir wt))))))

(defn unpublished-releases
  "Mergetyt release-PR:t, joita ei ole vielä julkaistu."
  [{:keys [cfg repo]}]
  (let [{:keys [main-branch]} (:release cfg)
        released (get-in cfg [:labels :released])]
    (->> (shell/json! ["gh" "pr" "list" "--repo" repo "--state" "merged"
                       "--head" (:base-branch cfg) "--base" main-branch
                       "--limit" "10" "--json" "number,body,labels"])
         (filter #(str/includes? (or (:body %) "") marker))
         (remove #((gh/label-names %) released))
         (sort-by :number))))

(defn publish-merged!
  "Julkaisee kaikki mergetyt, julkaisemattomat release-PR:t."
  [{:keys [cfg] :as ctx}]
  (when (get-in cfg [:release :publish])
    (doall (map #(publish! ctx %) (unpublished-releases ctx)))))
