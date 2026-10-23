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
   ;; run-next katselmoi ja mergeää jokaisen PR:n ennen seuraavaa issueta (Claude Code
   ;; omalla koneella). false: PR jää Actions-workflow'n katselmoitavaksi.
   :review-in-run-next true
   ;; Esim. "bb test" tai "npm test". nil = ei testiajoa ennen PR:ää.
   :test-cmd nil
   ;; Agentti ei saa muuttaa näitä polkuja. Muutos pysäyttää työn.
   :protected-paths [".github/workflows/" ".agent/"]
   ;; Kieli, jolla agentit kirjoittavat kommentit ja yhteenvedot.
   :language "English"
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
            :in-develop "agent:in-develop"
            ;; Issueen: katselmoi, mutta älä mergeä developiin. Sinä mergeät.
            :hold "agent:hold"
            ;; PR: katselmointi läpäisty, odottaa sinun mergeäsi developiin.
            :approved "agent:approved"
            ;; Release-PR: julkaistu (tagi ja GitHub Release tehty).
            :released "agent:released"}
   ;; :auto = linjasto mergeää developiin, kun katselmointi on puhdas.
   ;; :manual = jokainen PR jää odottamaan sinua (agent:approved). Yksittäisen issuen
   ;; voi pidättää myös :auto-tilassa labelilla agent:hold.
   ;; Valinnainen GitHub Projectin Status-synkka. Päällä, kun projekti tunnetaan:
   ;; :owner/:number tässä tai jonon :queue :project -asetuksessa. Vaatii gh:lle
   ;; project-oikeuden (gh auth refresh -s project). Sarakkeet nimillä, ei id:illä.
   :project-status {:enabled true
                    :owner nil
                    :number nil
                    :field "Status"
                    ;; label → sarake. Labelit ilman saraketta eivät siirrä korttia
                    ;; (needs-human jättää kortin paikalleen, punainen label kertoo tilan).
                    :columns {:ready "Ready"
                              :working "In progress"
                              :review "In review"
                              :approved "In review"
                              :merging "In review"
                              :in-develop "Done"}}
   :merge-to-develop :auto
   :merge {:method "squash" :delete-branch true}
   ;; Pysyvä release-PR develop → main, johon kerätään developiin mergetyt issuet.
   :release {:enabled true
             :main-branch "main"
             :title "Release: develop → main"
             ;; Agentin kirjoittamat release notes release-PR:ään ja GitHub Releaseen.
             :notes true
             ;; Julkaisu, kun release-PR on mergetty mainiin: versio, CHANGELOG, tagi, GitHub Release.
             :publish true
             :version-file "package.json"   ; nil = ei version nostoa tiedostoon
             :changelog "CHANGELOG.md"      ; nil = ei changelogia
             :tag-prefix "v"}
   :roles {:implementer {:runner :pi
                         ;; Malli. nil = Pi:n oma oletusmalli.
                         ;; Etusijajärjestys: komentorivin --model > projektin config > tämä oletus.
                         :model "opencode-go/minimax-m3"
                         ;; Muut lisäliput pi:lle. Tarkista ne komennolla `pi --help`.
                         :args []}
           :reviewer-a {:runner :claude-code
                        :model "sonnet"
                        :focus "correctness, edge cases, error handling and test coverage"
                        :allowed-tools read-only-tools}
           :reviewer-b {:runner :claude-code
                        :model "opus"
                        :focus "design, readability, maintainability and security"
                        :allowed-tools read-only-tools}
           :release-notes {:runner :claude-code
                           :model "sonnet"
                           ;; Ei työkaluja: kaikki tieto annetaan promptissa.
                           :allowed-tools []}
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

(defn model-from-args
  "Palauttaa --model-lipun arvon argumenttilistasta, tai nil."
  [args]
  (second (drop-while #(not= "--model" %) args)))

(defn merge-project-config
  "Yhdistää projektin konfiguraation oletuksiin. Jos projekti antaa implementerin
  mallin vanhaan tapaan :args-listassa, se voittaa oletusmallin."
  [project]
  (let [impl (get-in project [:roles :implementer])
        legacy-model (when-not (contains? impl :model) (model-from-args (:args impl)))]
    (cond-> (deep-merge defaults (or project {}))
      legacy-model (assoc-in [:roles :implementer :model] legacy-model))))

(defn load-config
  "Lukee projektin .agent/config.edn:n (jos on) ja yhdistää sen oletuksiin."
  [project-dir]
  (let [p (project-config-path project-dir)]
    (merge-project-config (when (fs/exists? p) (edn/read-string (slurp (str p)))))))

(defn worktree-root [cfg project-dir]
  (or (:worktree-root cfg)
      ;; canonicalize: "/x/proj/." → "/x/proj", jotta worktreet tulevat projektin viereen.
      (let [dir (fs/canonicalize project-dir)]
        (str (fs/path (fs/parent dir) (str (fs/file-name dir) "-worktrees"))))))
