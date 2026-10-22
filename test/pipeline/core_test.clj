(ns pipeline.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pipeline.agents :as agents]
            [pipeline.cli :as cli]
            [pipeline.events :as events]
            [pipeline.config :as config]
            [pipeline.git :as git]
            [pipeline.implement :as implement]
            [pipeline.prompts :as prompts]
            [pipeline.queue :as queue]
            [pipeline.release]
            [pipeline.status]
            [pipeline.dashboard]
            [pipeline.review :as review]))

(deftest deep-merge-test
  (let [cfg (config/deep-merge config/defaults
                               {:test-cmd "bb test"
                                :roles {:reviewer-b {:model "haiku"}}})]
    (is (= "bb test" (:test-cmd cfg)))
    (is (= "haiku" (get-in cfg [:roles :reviewer-b :model])))
    (testing "muut roolin avaimet säilyvät"
      (is (= :claude-code (get-in cfg [:roles :reviewer-b :runner])))
      (is (seq (get-in cfg [:roles :reviewer-b :allowed-tools]))))))

(deftest render-test
  (is (= "Issue #42: Hei {{tuntematon}}"
         (prompts/render "Issue #{{issue-number}}: {{ greeting }} {{tuntematon}}"
                         {:issue-number 42 :greeting "Hei"})))
  (testing "arvoissa olevia paikkoja ei korvata uudelleen"
    (is (= "x {{diff}}" (prompts/render "x {{a}}" {:a "{{diff}}" :diff "EI"})))))

(deftest default-prompts-exist
  (doseq [t ["implementer" "implementer-fix-tests" "reviewer-a" "reviewer-b" "fixer"]]
    (is (str/includes? (prompts/prompt "/nonexistent" t {:language "Finnish"}) "Finnish") t)))

(deftest extract-json-test
  (is (= {:a 1} (agents/extract-json "Tässä:\n```json\n{\"a\": 1}\n```\nvalmis")))
  (is (= {:findings []} (agents/extract-json "blah {\"findings\": []} blah")))
  (is (nil? (agents/extract-json "ei jsonia"))))

(deftest branch-test
  (let [cfg config/defaults]
    (is (= "agent/issue-42" (implement/branch-name cfg 42)))
    (is (= 42 (implement/issue-number-from-branch cfg "agent/issue-42")))
    (is (nil? (implement/issue-number-from-branch cfg "feature/x")))))

(deftest protected-test
  (is (= [".github/workflows/ci.yml"]
         (git/protected-violations ["src/a.clj" ".github/workflows/ci.yml"]
                                   [".github/workflows/" ".agent/"]))))

(deftest queue-sort-test
  (let [issue (fn [n & labels] {:number n :labels (map (fn [l] {:name l}) labels)})
        sorted (queue/sort-by-labels ["prio:1" "prio:2"]
                                     [(issue 5) (issue 3 "prio:2") (issue 9 "prio:1") (issue 1)])]
    (is (= [9 3 1 5] (map :number sorted))))
  (let [items [{:priority "P2" :content {:number 1}}
               {:priority "P0" :content {:number 7}}
               {:content {:number 2}}
               {:priority "P0" :content {:number 3}}]]
    (is (= [3 7 1 2]
           (map #(get-in % [:content :number])
                (queue/sort-project-items {:field "Priority" :order ["P0" "P1" "P2"]} items))))))

(deftest rounds-test
  (is (= 0 (review/last-round [])))
  (is (= 2 (review/last-round ["<!-- linjasto:round=1 -->\nx" "ihmisen kommentti"
                               "<!-- linjasto:round=2 -->" nil]))))

(deftest blocking-test
  (let [a (review/number-findings "A" [{:severity "blocking" :title "bugi"}
                                       {:severity "blocking" :title "makuasia"}
                                       {:severity "nit" :title "kirjoitusvirhe"}])
        b (review/number-findings "B" [{:severity "blocking" :title "tietoturva"}
                                       {:severity "suggestion" :title "nimeä"}])
        disputes [{:id "A2" :reason "ei kuulu tähän issueen"}]
        blocking (review/blocking-findings a b disputes)]
    (is (= ["A1" "B1"] (map :id blocking)))
    (let [body (review/review-body {:round 1 :max-rounds 3 :findings (into a b)
                                    :blocking blocking :disputes disputes})]
      (is (str/starts-with? body "<!-- linjasto:round=1 -->"))
      (is (= 1 (review/last-round [body])))
      (is (str/includes? body "Blocking (2)"))
      (is (str/includes? body "A2**: ei kuulu")))))

(deftest inline-comments-test
  (is (= [{:path "src/a.clj" :line 3 :side "RIGHT" :body "**A1 · blocking** t\n\nb"}]
         (review/inline-comments [{:id "A1" :severity "blocking" :title "t" :body "b"
                                   :file "src/a.clj" :line 3}
                                  {:id "A2" :severity "nit" :title "ilman sijaintia" :body "x"}]))))

(deftest parse-args-test
  (is (= {:project "/p" :args ["42"]} (cli/parse-args ["42" "--project" "/p"]))))

(deftest model-override-test
  (is (= [:implementer "opencode-go/minimax-m3"] (cli/parse-model-override "opencode-go/minimax-m3")))
  (is (= [:reviewer-b "sonnet"] (cli/parse-model-override "reviewer-b=sonnet")))
  (testing "tuntematon etuliite on osa mallin nimeä"
    (is (= [:implementer "foo=bar"] (cli/parse-model-override "foo=bar"))))
  (is (= {:models ["a" "fixer=b"] :args ["13"]}
         (cli/parse-args ["13" "--model" "a" "--model" "fixer=b"])))
  (let [cfg (cli/apply-model-overrides config/defaults ["m1" "fixer=haiku"])]
    (is (= "m1" (get-in cfg [:roles :implementer :model])))
    (is (= "haiku" (get-in cfg [:roles :fixer :model])))
    (is (= :claude-code (get-in cfg [:roles :fixer :runner])))))

(deftest pi-command-test
  (is (= ["pi" "--mode" "json" "P"] (agents/pi-command {:args []} "P")))
  (is (= ["pi" "--mode" "json" "--model" "m" "P"] (agents/pi-command {:model "m"} "P")))
  (testing ":model voittaa :args-listan --model-lipun"
    (is (= ["pi" "--mode" "json" "--model" "uusi" "--thinking" "high" "P"]
           (agents/pi-command {:model "uusi" :args ["--model" "vanha" "--thinking" "high"]} "P")))))

(deftest default-model-test
  (is (= "opencode-go/minimax-m3" (get-in (config/merge-project-config nil) [:roles :implementer :model])))
  (testing "projektin :model voittaa oletuksen"
    (is (= "x" (get-in (config/merge-project-config {:roles {:implementer {:model "x"}}})
                       [:roles :implementer :model]))))
  (testing "vanha tapa: --model :args-listassa voittaa oletuksen"
    (is (= "y" (get-in (config/merge-project-config {:roles {:implementer {:args ["--model" "y"]}}})
                       [:roles :implementer :model]))))
  (testing "komentorivi voittaa kaiken"
    (is (= "z" (get-in (cli/apply-model-overrides
                        (config/merge-project-config {:roles {:implementer {:model "x"}}}) ["z"])
                       [:roles :implementer :model])))))

(defn- collect-events
  "Ajaa f:n niin, että tapahtumat kerätään listaan eikä tulosteta."
  [f]
  (let [seen (atom [])]
    (with-redefs [events/emit! (fn [t m] (swap! seen conj (assoc m :type (name t))))]
      [(f) @seen])))

(deftest pi-stream-test
  (let [lines ["{\"type\":\"session\",\"version\":3}"
               "{\"type\":\"tool_execution_start\",\"toolCallId\":\"c1\",\"toolName\":\"read\",\"args\":{\"path\":\"src/chart.ts\"}}"
               "{\"type\":\"tool_execution_start\",\"toolCallId\":\"c2\",\"toolName\":\"bash\",\"args\":{\"command\":\"npm test\"}}"
               "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Valmis.\"}]}}"
               "{\"type\":\"agent_settled\",\"aborted\":false}"]
        [state seen] (collect-events #(reduce agents/pi-line {} lines))]
    (is (= "Valmis." (:text state)))
    (is (= [{:tool "lukee" :detail "src/chart.ts"} {:tool "ajaa" :detail "npm test"}]
           (map #(select-keys % [:tool :detail]) (filter #(= "tool" (:type %)) seen)))))
  (testing "virhe kerätään"
    (let [[state _] (collect-events
                     #(agents/pi-line {} "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"error\",\"error\":\"403 FreeTierError\"}}"))]
      (is (= ["403 FreeTierError"] (:errors state))))))

(deftest claude-stream-test
  (let [lines ["{\"type\":\"system\",\"subtype\":\"init\"}"
               "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Katson diffin.\"},{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"git diff origin/develop...HEAD\"}}]}}"
               "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"structured_output\":{\"findings\":[]}}"]
        [state seen] (collect-events #(reduce agents/claude-line {} lines))]
    (is (= {:findings []} (get-in state [:result :structured_output])))
    (is (= ["agent-text" "tool"] (map :type seen)))
    (is (= "ajaa" (:tool (second seen))))))

(deftest events-test
  (is (= "4 min 5 s" (events/duration-str 245000)))
  (is (= "[implementer] muokkaa src/a.ts"
         (events/describe {:type "tool" :role "implementer" :tool "muokkaa" :detail "src/a.ts"})))
  (let [evs [{:type "run-start" :run "1"} {:type "phase" :run "1"}
             {:type "run-start" :run "2"} {:type "phase" :run "2" :msg "x"}]]
    (is (= ["2" "2"] (map :run (events/last-run evs))))))

(deftest worktree-root-test
  (let [tmp (str (babashka.fs/create-temp-dir))
        proj (str (babashka.fs/create-dirs (babashka.fs/path tmp "proj")))]
    (testing "\".\"-päätteinen polku ei vie worktreetä projektin sisään"
      (is (= (str (babashka.fs/path (babashka.fs/canonicalize tmp) "proj-worktrees"))
             (config/worktree-root config/defaults (str proj "/.")))))
    (is (clojure.string/ends-with? (events/log-file (str proj "/.")) "/proj.jsonl"))))

(deftest release-body-test
  (let [b0 (pipeline.release/initial-body "develop" "main")
        b1 (pipeline.release/add-line b0 {:issue 13 :pr 14 :title "change timeframe"})
        b2 (pipeline.release/add-line b1 {:issue 13 :pr 14 :title "change timeframe"})
        b3 (pipeline.release/add-line b2 {:issue 15 :pr 16 :title "x"})]
    (is (str/includes? b1 "- Closes #13 · PR #14 · change timeframe"))
    (is (= b1 b2) "sama issue ei tuplaannu")
    (is (str/includes? b3 "- Closes #15 · PR #16 · x"))
    (is (not (pipeline.release/includes-issue? b1 1)) "#1 ei osu riviin #13")
    (testing "käsin muokattu runko ilman merkkiä"
      (is (str/includes? (pipeline.release/add-line "oma teksti" {:issue 2}) "- Closes #2")))))

(deftest status-summary-test
  (let [evs [{:type "run-start" :run "1" :ts "2026-10-19T10:00:00Z" :pid 1 :command "run-issue"}
             {:type "phase" :msg "luodaan worktree" :ts "2026-10-19T10:00:01Z" :issue 13}
             {:type "agent-start" :role "implementer" :model "m" :ts "2026-10-19T10:00:02Z"}
             {:type "tool" :role "implementer" :tool "lukee" :ts "2026-10-19T10:00:03Z"}]
        now (.toEpochMilli (java.time.Instant/parse "2026-10-19T10:01:02Z"))
        sm (pipeline.status/summarize evs now (constantly true))]
    (is (= "implementer työskentelee" (:phase sm)))
    (is (= "implementer" (get-in sm [:agent :role])))
    (is (= 60000 (get-in sm [:agent :elapsed-ms])))
    (is (= 13 (:issue sm)))
    (is (= "keskeytynyt" (:state (pipeline.status/summarize evs now (constantly false)))))))

(deftest attention-parsing-test
  (let [body (pipeline.release/add-line
              (pipeline.release/add-line (pipeline.release/initial-body "develop" "main")
                                         {:issue 13 :pr 14 :title "change timeframe"})
              {:issue 15})]
    (is (= [{:issue 13 :pr 14 :title "change timeframe"} {:issue 15 :pr nil :title nil}]
           (pipeline.dashboard/release-items body))))
  (is (= "Testit epäonnistuvat yhä:"
         (pipeline.dashboard/stop-reason "🛑 Linjasto pysähtyi tämän issuen kohdalla.\n\n```\nTestit epäonnistuvat yhä:\nFAIL x\n```\n\nKorjaa…")))
  (is (= "blokkaavia löydöksiä on yhä 3 kierroksen jälkeen. Ihmisen vuoro."
         (pipeline.dashboard/stop-reason "🛑 Linjasto pysähtyi: blokkaavia löydöksiä on yhä 3 kierroksen jälkeen. Ihmisen vuoro.")))
  (is (nil? (pipeline.dashboard/stop-reason "tavallinen kommentti"))))

(deftest hold-test
  (is (false? (review/hold? config/defaults ["bug"])))
  (is (true? (review/hold? config/defaults ["agent:hold"])))
  (is (true? (review/hold? (assoc config/defaults :merge-to-develop :manual) []))))

(deftest release-notes-test
  (let [body (pipeline.release/add-line (pipeline.release/initial-body "develop" "main")
                                        {:issue 13 :pr 14 :title "change timeframe"})
        b1 (pipeline.release/with-notes body {:version "0.2.0" :bump "minor" :notes "### New\n\n- Thing"})
        b2 (pipeline.release/with-notes b1 {:version "0.2.0" :bump "minor" :notes "### New\n\n- Other"})]
    (testing "notes before the Closes list, replaced not duplicated"
      (is (< (str/index-of b1 "Release notes") (str/index-of b1 "Closes #13")))
      (is (= 1 (count (re-seq #"Release notes" b2))))
      (is (str/includes? b2 "- Other")))
    (is (= "minor" (get-in (pipeline.release/notes-block b1) [:attrs "bump"])))
    (is (false? (pipeline.release/hand-edited? b1)))
    (is (true? (pipeline.release/hand-edited? (str/replace b1 "- Thing" "- My own words"))))
    (is (= "### New\n\n- Thing" (pipeline.release/notes-text b1)))
    (is (= "- change timeframe" (pipeline.release/notes-text body)))))

(deftest version-test
  (is (= [0 2 0] (pipeline.release/bump-version [0 1 3] "minor")))
  (is (= [0 1 4] (pipeline.release/bump-version [0 1 3] "patch")))
  (is (= [0 0 1] (pipeline.release/bump-version nil "patch")))
  (is (= [1 2 0] (pipeline.release/max-version [[0 9 9] nil [1 2 0] [1 1 5]])))
  (is (= [0 1 0] (pipeline.release/parse-version "v0.1.0")))
  (let [lock "{\n  \"name\": \"x\",\n  \"version\": \"0.1.0\",\n  \"packages\": {\n    \"\": {\n      \"version\": \"0.1.0\"\n    },\n    \"node_modules/y\": {\n      \"version\": \"0.1.0\"\n    }\n  }\n}"]
    (is (= 2 (count (re-seq #"\"0\.2\.0\"" (pipeline.release/set-json-version lock "0.1.0" "0.2.0")))))
    (is (str/includes? (pipeline.release/set-json-version lock "0.1.0" "0.2.0") "\"node_modules/y\": {\n      \"version\": \"0.1.0\"")))
  (is (= "# Changelog\n\n## v0.2.0 – 2026-10-21\n\n- A\n\n## v0.1.0 – x\n"
         (pipeline.release/prepend-changelog "# Changelog\n\n## v0.1.0 – x\n" "0.2.0" "2026-10-21" "- A")))
  (is (= "# Changelog\n\n## v0.1.0 – d\n\n- A\n"
         (pipeline.release/prepend-changelog nil "0.1.0" "d" "- A"))))

(deftest release-plan-test
  (testing "ensimmäinen yritys: PR:n version= käytetään"
    (is (= {:old [0 1 0] :new "0.2.0" :resume? false}
           (pipeline.release/release-plan [0 1 0] {"version" "0.2.0" "bump" "minor"} #{"v0.1.0"} "v"))))
  (testing "keskeytynyt julkaisu: tagi on jo, ei nostoa uudelleen"
    (is (= {:old [0 2 0] :new "0.2.0" :resume? true}
           (pipeline.release/release-plan [0 2 0] {"version" "0.2.0" "bump" "minor"} #{"v0.1.0" "v0.2.0"} "v"))))
  (testing "ilman version=-tietoa nostetaan bumpin mukaan"
    (is (= "0.1.1" (:new (pipeline.release/release-plan [0 1 0] {} #{} "v"))))))

(deftest stop-comment-test
  (let [c (review/stop-comment-for-issue config/defaults 34 "the fixer made no changes.")]
    (is (str/includes? c "PR #34"))
    (is (str/includes? c "agent:ready"))
    (is (= "the fixer made no changes." (pipeline.dashboard/stop-reason c)))))
