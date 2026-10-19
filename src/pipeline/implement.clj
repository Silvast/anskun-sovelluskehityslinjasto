(ns pipeline.implement
  "Implementer-vaihe: issue → worktree → agentti → testit → push → PR.
  Ajetaan omalla koneella."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [pipeline.agents :as agents]
            [pipeline.config :as config]
            [pipeline.git :as git]
            [pipeline.github :as gh]
            [pipeline.prompts :as prompts]
            [pipeline.shell :as shell]))

(defn branch-name [cfg n]
  (str (:branch-prefix cfg) n))

(defn issue-number-from-branch
  "agent/issue-42 → 42. Palauttaa nil, jos haara ei ole linjaston."
  [cfg branch]
  (when (and branch (str/starts-with? branch (:branch-prefix cfg)))
    (parse-long (subs branch (count (:branch-prefix cfg))))))

(defn worktree-path [cfg project-dir n]
  (str (fs/path (config/worktree-root cfg project-dir) (str "issue-" n))))

(defn- run-tests [cfg dir]
  (if-let [cmd (:test-cmd cfg)]
    (do (shell/log "Ajetaan testit:" cmd)
        (let [{:keys [exit out err]} (shell/sh ["bash" "-lc" cmd] {:dir dir})]
          {:ok? (zero? exit) :output (shell/tail (str out "\n" err) 6000)}))
    {:ok? true :output nil}))

(defn- check-protected! [cfg dir]
  (let [bad (git/protected-violations
             (git/changed-files dir (:remote cfg) (:base-branch cfg))
             (:protected-paths cfg))]
    (when (seq bad)
      (throw (ex-info (str "Muutoksia suojattuihin polkuihin: " (str/join ", " bad))
                      {:type ::protected :files bad})))))

(defn- fail! [{:keys [cfg repo]} n e]
  (let [labels (:labels cfg)
        msg (or (ex-message e) (str e))]
    (shell/log "Issue" n "pysähtyi:" msg)
    (gh/set-labels! repo n {:remove [(:working labels)] :add [(:needs-human labels)]})
    (gh/comment! repo n (str "🛑 Linjasto pysähtyi tämän issuen kohdalla.\n\n```\n"
                             (shell/tail msg 3000) "\n```\n\n"
                             "Korjaa tilanne ja palauta label `" (:ready labels)
                             "`, niin linjasto yrittää uudelleen."))
    {:status :needs-human :issue n :error msg}))

(defn run-issue
  "Toteuttaa issuen n ja avaa PR:n. Palauttaa {:status …}."
  [{:keys [cfg project-dir repo] :as ctx} n]
  (let [labels (:labels cfg)
        issue (gh/issue repo n)
        branch (branch-name cfg n)
        existing (gh/open-pr-for-branch repo branch)]
    (cond
      (not= "OPEN" (:state issue))
      (do (shell/log "Issue" n "ei ole auki, ohitetaan.") {:status :skipped :issue n})

      existing
      (do (shell/log "Issuella" n "on jo avoin PR" (:url existing) "– ohitetaan.")
          {:status :skipped :issue n :pr (:number existing)})

      :else
      (try
        (shell/log "Aloitetaan issue" n "–" (:title issue))
        (gh/set-labels! repo n {:remove [(:ready labels) (:needs-human labels)]
                                :add [(:working labels)]})
        (let [wt (git/prepare-worktree! project-dir (worktree-path cfg project-dir n)
                                        branch (:remote cfg) (:base-branch cfg))
              role (get-in cfg [:roles :implementer])
              vars {:issue-number n
                    :issue-title (:title issue)
                    :issue-body (or (:body issue) "")
                    :base-branch (:base-branch cfg)
                    :test-cmd (or (:test-cmd cfg) "(ei määritelty)")
                    :language (:language cfg)}
              result (agents/run-agent role {:dir wt
                                             :prompt (prompts/prompt project-dir "implementer" vars)})]
          (when-not (git/commit-all! wt (str "#" n ": " (:title issue)))
            (throw (ex-info "Implementer ei tehnyt muutoksia." {})))
          (check-protected! cfg wt)
          ;; Testit. Jos ne epäonnistuvat, implementer saa yhden korjausyrityksen.
          (let [t1 (run-tests cfg wt)]
            (when-not (:ok? t1)
              (shell/log "Testit epäonnistuivat, implementer yrittää korjata.")
              (agents/run-agent role {:dir wt
                                      :prompt (prompts/prompt project-dir "implementer-fix-tests"
                                                              (assoc vars :test-output (:output t1)))})
              (git/commit-all! wt (str "#" n ": korjaa testit"))
              (check-protected! cfg wt)
              (let [t2 (run-tests cfg wt)]
                (when-not (:ok? t2)
                  (throw (ex-info (str "Testit epäonnistuvat yhä:\n" (:output t2)) {}))))))
          (git/push! wt (:remote cfg) branch)
          (let [pr-n (gh/create-pr! repo {:base (:base-branch cfg)
                                          :head branch
                                          :title (str "#" n ": " (:title issue))
                                          :body (str "Linjasto toteutti issuen #" n ".\n\n"
                                                     "Linjasto-issue: #" n "\n\n"
                                                     "### Implementerin yhteenveto\n\n"
                                                     (shell/tail (:text result) 4000))})]
            (gh/set-labels! repo n {:remove [(:working labels)] :add [(:review labels)]})
            ;; PR:n label käynnistää review-workflow'n Actionsissa.
            (gh/add-labels! repo pr-n [(:review labels)])
            (git/remove-worktree! project-dir wt)
            (shell/log "PR avattu:" pr-n)
            {:status :pr-opened :issue n :pr pr-n}))
        (catch Exception e
          (fail! ctx n e))))))
