(ns pipeline.agents
  "Runnerit: yksi agenttiajo yhdellä roolilla. Runner valitaan roolin :runner-avaimella.

  Jokainen runner palauttaa {:text <agentin vastaus> :data <jäsennetty JSON tai nil>}."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

(defmulti run-agent
  "role: roolin asetukset configista. opts: {:dir :prompt :schema}."
  (fn [role _opts] (:runner role)))

(defmethod run-agent :claude-code
  [{:keys [model allowed-tools args]} {:keys [dir prompt schema]}]
  ;; Prompti annetaan stdinissä. --allowedTools on variadinen, joten se tulee viimeisenä.
  (let [cmd (cond-> ["claude" "-p" "--output-format" "json"]
              model (into ["--model" model])
              schema (into ["--json-schema" (json/generate-string schema)])
              (seq args) (into args)
              (seq allowed-tools) (into (cons "--allowedTools" allowed-tools)))
        out (shell/sh! cmd {:dir dir :in prompt :err-inherit? true})
        res (json/parse-string out true)]
    (when (:is_error res)
      (throw (ex-info (str "Claude Code -ajo epäonnistui: " (:result res)) {:result res})))
    {:text (:result res)
     :data (when schema
             (or (:structured_output res) (extract-json (:result res))))}))

(defmethod run-agent :pi
  [{:keys [args]} {:keys [dir prompt schema]}]
  ;; Pi:n print-tila: `pi -p <prompt>`. Lisäliput (provider, malli) tulevat :args-avaimesta.
  (let [out (shell/sh! (-> ["pi"] (into args) (into ["-p" prompt]))
                       {:dir dir :err-inherit? true})]
    {:text out :data (when schema (extract-json out))}))

(defmethod run-agent :command
  [{:keys [command]} {:keys [dir prompt schema]}]
  ;; Mikä tahansa komento, joka lukee promptin stdinistä ja kirjoittaa vastauksen
  ;; stdoutiin. Esim. paikallisen mallin wrapper-skripti.
  (let [out (shell/sh! command {:dir dir :in prompt :err-inherit? true})]
    {:text out :data (when schema (extract-json out))}))

(defmethod run-agent :default [role _]
  (throw (ex-info (str "Tuntematon runner: " (:runner role)) {:role role})))
