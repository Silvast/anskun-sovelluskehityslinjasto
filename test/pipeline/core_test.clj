(ns pipeline.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pipeline.agents :as agents]
            [pipeline.cli :as cli]
            [pipeline.config :as config]
            [pipeline.git :as git]
            [pipeline.implement :as implement]
            [pipeline.prompts :as prompts]
            [pipeline.queue :as queue]
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
      (is (str/includes? body "Blokkaavat (2)"))
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
  (is (= ["pi" "-p" "P"] (agents/pi-command {:args []} "P")))
  (is (= ["pi" "--model" "m" "-p" "P"] (agents/pi-command {:model "m"} "P")))
  (testing ":model voittaa :args-listan --model-lipun"
    (is (= ["pi" "--model" "uusi" "--thinking" "high" "-p" "P"]
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
