(ns pipeline.config
  "Oletusasetukset ja projektikohtaisen .agent/config.edn:n yhdistäminen."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def pipeline-root
  "Tämän repon juurihakemisto (src/pipeline/config.clj → juuri)."
  (-> *file* fs/absolutize fs/parent fs/parent fs/parent str))

(def read-only-tools
  ["Read" "Grep" "Glob"
   "Bash(git diff:*)" "Bash(git log:*)" "Bash(git show:*)" "Bash(git status:*)"])

(def defaults
  {:base-branch "develop"
   :remote "origin"
   :branch-prefix "agent/issue-"
   ;; nil → <projektin yläkansio>/<projektin nimi>-worktrees
   :worktree-root nil
   :max-rounds 3
   :max-issues-per-run 3
   ;; Esim. "bb test" tai "npm test". nil = ei testiajoa ennen PR:ää.
   :test-cmd nil
   ;; Agentti ei saa muuttaa näitä polkuja. Muutos pysäyttää työn.
   :protected-paths [".github/workflows/" ".agent/"]
   ;; Kieli, jolla agentit kirjoittavat kommentit ja yhteenvedot.
   :language "Finnish"
   :max-diff-chars 60000
   :queue {:source :labels
           ;; Ensimmäinen löytyvä label määrää prioriteetin. Ilman labelia jonon häntään.
           :priority-labels ["prio:1" "prio:2" "prio:3"]
           ;; :source :project -tilassa:
           ;; :project {:owner "Silvast" :number 1 :field "priority" :order ["P0" "P1" "P2"]}
           }
   :labels {:ready "agent:ready"
            :working "agent:working"
            :review "agent:review"
            :needs-human "agent:needs-human"
            :merging "agent:merging"
            :in-develop "agent:in-develop"}
   :merge {:method "squash" :delete-branch true}
   :roles {:implementer {:runner :pi
                         ;; Lisäargumentit pi:lle, esim. ["--provider" "anthropic" "--model" "…"].
                         ;; Tarkista liput komennolla `pi --help`.
                         :args []}
           :reviewer-a {:runner :claude-code
                        :model "sonnet"
                        :focus "correctness, edge cases, error handling and test coverage"
                        :allowed-tools read-only-tools}
           :reviewer-b {:runner :claude-code
                        :model "opus"
                        :focus "design, readability, maintainability and security"
                        :allowed-tools read-only-tools}
           :fixer {:runner :claude-code
                   :model "sonnet"
                   :allowed-tools ["Read" "Edit" "Write" "Grep" "Glob" "Bash"]}}})

(defn deep-merge
  "Yhdistää mapit rekursiivisesti. Vektorit ja muut arvot korvautuvat."
  [& ms]
  (apply merge-with
         (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b))
         ms))

(defn project-config-path [project-dir]
  (fs/path project-dir ".agent" "config.edn"))

(defn load-config
  "Lukee projektin .agent/config.edn:n (jos on) ja yhdistää sen oletuksiin."
  [project-dir]
  (let [p (project-config-path project-dir)
        project (when (fs/exists? p) (edn/read-string (slurp (str p))))]
    (deep-merge defaults (or project {}))))

(defn worktree-root [cfg project-dir]
  (or (:worktree-root cfg)
      (str (fs/path (fs/parent (fs/absolutize project-dir))
                    (str (fs/file-name (fs/absolutize project-dir)) "-worktrees")))))
