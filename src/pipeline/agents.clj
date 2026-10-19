(ns pipeline.agents
  "Runnerit: yksi agenttiajo yhdellä roolilla. Runner valitaan roolin :runner-avaimella.

  Pi ja Claude Code ajetaan suoratoistotilassa, jotta jokainen työkalukutsu
  näkyy tapahtumalokissa heti. Jokainen runner palauttaa
  {:text <agentin vastaus> :data <jäsennetty JSON tai nil>}."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [pipeline.events :as events]
            [pipeline.shell :as shell]))

(defn extract-json
  "Etsii tekstistä JSON-olion: ensin ```json-aidan sisältä, sitten ensimmäisen
  { ja viimeisen } väliltä. Palauttaa nil, jos jäsentäminen ei onnistu."
  [s]
  (when s
    (let [fenced (second (re-find #"(?s)```(?:json)?\s*(\{.*?\})\s*```" s))
          candidate (or fenced
                        (let [a (str/index-of s "{") b (str/last-index-of s "}")]
                          (when (and a b (< a b)) (subs s a (inc b)))))]
      (when candidate
        (try (json/parse-string candidate true)
             (catch Exception _ nil))))))

;; --- Työkalukutsujen kuvaus ------------------------------------------------------

(def tool-verbs
  {"read" "lukee" "edit" "muokkaa" "multiedit" "muokkaa" "write" "kirjoittaa"
   "bash" "ajaa" "grep" "etsii" "glob" "listaa" "find" "listaa" "ls" "listaa"
   "webfetch" "hakee" "websearch" "hakee"})

(defn- one-line [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn tool-event
  "Työkalukutsusta tapahtuman kentät: {:tool \"muokkaa\" :detail \"src/a.ts\"}."
  [tool-name args]
  (let [args (if (map? args) args {})
        detail (some #(get args %) [:file_path :path :command :pattern :url :query])]
    {:tool (get tool-verbs (str/lower-case (str tool-name)) (str tool-name))
     :detail (when detail (one-line detail 100))}))

(defn- emit-text! [s]
  (when-not (str/blank? s)
    (events/emit! :agent-text {:msg (one-line s 160)})))

;; --- Claude Code: stream-json --------------------------------------------------

(defn claude-command [{:keys [model allowed-tools args]} schema]
  ;; Prompti annetaan stdinissä. --allowedTools on variadinen, joten se tulee viimeisenä.
  (cond-> ["claude" "-p" "--output-format" "stream-json" "--verbose"]
    model (into ["--model" model])
    schema (into ["--json-schema" (json/generate-string schema)])
    (seq args) (into args)
    (seq allowed-tools) (into (cons "--allowedTools" allowed-tools))))

(defn claude-line
  "Käsittelee yhden stream-json-rivin. Palauttaa päivitetyn tilan; kirjaa tapahtumat."
  [state line]
  (let [e (try (json/parse-string line true) (catch Exception _ nil))]
    (case (:type e)
      "assistant" (do (doseq [c (get-in e [:message :content])]
                        (case (:type c)
                          ;; StructuredOutput on Clauden sisäinen tapa palauttaa --json-schema-vastaus.
                          "tool_use" (when-not (= "StructuredOutput" (:name c))
                                       (events/emit! :tool (tool-event (:name c) (:input c))))
                          "text" (emit-text! (:text c))
                          nil))
                      state)
      "result" (assoc state :result e)
      state)))

(defmulti run-agent*
  "role: roolin asetukset configista. opts: {:dir :prompt :schema}."
  (fn [role _opts] (:runner role)))

(defmethod run-agent* :claude-code
  [role {:keys [dir prompt schema]}]
  (let [state (atom {})
        {:keys [exit]} (shell/stream! (claude-command role schema)
                                      {:dir dir :in prompt
                                       :on-line #(swap! state claude-line %)})
        res (:result @state)]
    (when (or (nil? res) (:is_error res) (not (zero? exit)))
      (throw (ex-info (str "Claude Code -ajo epäonnistui (exit " exit "): "
                           (or (:result res) "ei tulosta"))
                      {:result res})))
    {:text (:result res)
     :data (when schema
             (or (:structured_output res) (extract-json (:result res))))}))

;; --- Pi: --mode json -------------------------------------------------------------

(defn without-flag
  "Poistaa argumenteista lipun ja sen arvon, esim. [\"--model\" \"x\"]."
  [args flag]
  (loop [[a & more] args, acc []]
    (cond (nil? a) acc
          (= a flag) (recur (rest more) acc)
          :else (recur more (conj acc a)))))

(defn pi-command
  "`pi --mode json [--model m] <args> <prompt>`. :model voittaa :args-listan --model-lipun."
  [{:keys [model args]} prompt]
  (cond-> ["pi" "--mode" "json"]
    model (into ["--model" model])
    true (into (if model (without-flag args "--model") args))
    true (conj prompt)))

(defn- text-content [message]
  (->> (:content message)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn pi-line
  "Käsittelee yhden Pi:n JSON-tapahtuman. Palauttaa päivitetyn tilan; kirjaa tapahtumat."
  [state line]
  (let [e (try (json/parse-string line true) (catch Exception _ nil))]
    (cond
      (nil? e)
      (update state :raw (fnil conj []) line)

      (= "tool_execution_start" (:type e))
      (do (events/emit! :tool (tool-event (:toolName e) (:args e))) state)

      (and (= "message_end" (:type e)) (= "assistant" (get-in e [:message :role])))
      (let [t (text-content (:message e))]
        (emit-text! t)
        (cond-> state (not (str/blank? t)) (assoc :text t)))

      (and (= "message_update" (:type e))
           (= "error" (get-in e [:assistantMessageEvent :type])))
      (let [{:keys [error reason]} (:assistantMessageEvent e)
            msg (str (or error reason "mallin virhe"))]
        (events/emit! :error {:msg (one-line msg 200)})
        (update state :errors (fnil conj []) msg))

      (and (= "auto_retry_end" (:type e)) (false? (:success e)))
      (update state :errors (fnil conj []) (str (:finalError e)))

      :else state)))

(defmethod run-agent* :pi
  [role {:keys [dir prompt schema]}]
  (let [state (atom {})
        {:keys [exit]} (shell/stream! (pi-command role prompt)
                                      {:dir dir :on-line #(swap! state pi-line %)})
        {:keys [text errors raw]} @state
        text (or text (str/join "\n" raw))]
    (when (or (not (zero? exit)) (seq errors))
      (throw (ex-info (str "Pi-ajo epäonnistui (exit " exit ")"
                           (when (seq errors) (str ": " (str/join "; " errors)))
                           (when (and (empty? errors) (not (str/blank? text)))
                             (str "\n" (shell/tail text 2000))))
                      {:errors errors})))
    {:text text :data (when schema (extract-json text))}))

;; --- Mikä tahansa komento ---------------------------------------------------------

(defmethod run-agent* :command
  [{:keys [command]} {:keys [dir prompt schema]}]
  ;; Komento lukee promptin stdinistä ja kirjoittaa vastauksen stdoutiin.
  ;; Esim. paikallisen mallin wrapper-skripti. Rivit näkyvät lokissa sellaisinaan.
  (let [lines (atom [])
        {:keys [exit]} (shell/stream! command {:dir dir :in prompt
                                               :on-line #(swap! lines conj %)})
        out (str/join "\n" @lines)]
    (when-not (zero? exit)
      (throw (ex-info (str "Komento epäonnistui (exit " exit ")\n" (shell/tail out 2000)) {})))
    (emit-text! out)
    {:text out :data (when schema (extract-json out))}))

(defmethod run-agent* :default [role _]
  (throw (ex-info (str "Tuntematon runner: " (:runner role)) {:role role})))

;; --- Julkinen rajapinta ------------------------------------------------------------

(defn run-agent
  "Ajaa roolin agentin ja kirjaa alun, työkalukutsut ja lopun tapahtumalokiin.
  opts: {:role <roolin nimi keywordina> :dir :prompt :schema}."
  [role-cfg {:keys [role] :as opts}]
  (binding [events/*ctx* (cond-> events/*ctx* role (assoc :role (name role)))]
    (events/emit! :agent-start {:model (:model role-cfg)
                                :runner (some-> (:runner role-cfg) name)})
    (let [t0 (System/currentTimeMillis)
          ms #(- (System/currentTimeMillis) t0)]
      (try
        (let [r (run-agent* role-cfg opts)]
          (events/emit! :agent-end {:ok true :ms (ms)})
          r)
        (catch Exception e
          (events/emit! :agent-end {:ok false :ms (ms)})
          (throw e))))))
