(ns pipeline.status
  "`linjasto status`: viimeisimmän ajon tila tapahtumalokista."
  (:require [clojure.string :as str]
            [pipeline.events :as events])
  (:import (java.time Instant)))

(defn- ms-since [ts now-ms]
  (try (- now-ms (.toEpochMilli (Instant/parse ts))) (catch Exception _ 0)))

(defn- last-of [type evs]
  (last (filter #(= type (:type %)) evs)))

(defn current-agent
  "Viimeisin agent-start, jota ei ole vielä seurannut agent-end samalle roolille."
  [evs]
  (let [start (last-of "agent-start" evs)]
    (when start
      (let [after (drop-while #(not= start %) evs)]
        (when-not (some #(and (= "agent-end" (:type %)) (= (:role %) (:role start))) after)
          start)))))

(defn summarize
  "Puhdas yhteenveto viimeisimmästä ajosta."
  [evs now-ms alive?]
  (when (seq evs)
    (let [start (first evs)
          end (last-of "run-end" evs)
          ;; Kun agentti on aloittanut viimeisimmän vaiheen jälkeen, vaihe on agentin työ.
          phase (let [e (last (filter #(#{"phase" "agent-start"} (:type %)) evs))]
                  (if (= "agent-start" (:type e))
                    {:msg (str (:role e) " työskentelee")}
                    e))
          agent (current-agent evs)
          running? (and (nil? end) (alive? (:pid start)))]
      {:project (:project start)
       :command (:command start)
       :target (:target start)
       :started (:ts start)
       :state (cond end (str "valmis: " (:status end))
                    running? "käynnissä"
                    :else "keskeytynyt")
       :running? running?
       :elapsed-ms (if end
                     (- (ms-since (:ts start) now-ms) (ms-since (:ts end) now-ms))
                     (ms-since (:ts start) now-ms))
       :issue (some :issue (reverse evs))
       :issue-title (some :title (reverse (filter #(= "phase" (:type %)) evs)))
       :pr (some :pr (reverse evs))
       :phase (:msg phase)
       :agent (when (and agent running?)
                {:role (:role agent) :model (:model agent)
                 :elapsed-ms (ms-since (:ts agent) now-ms)})
       :recent (take-last 12 (remove #(= "run-start" (:type %)) evs))})))

(defn render [{:keys [project command target started state elapsed-ms issue issue-title
                      pr phase agent recent]}]
  (str/join
   "\n"
   (concat
    [(str project " · " command (when target (str " " target))
          " · aloitettu " (events/clock started)
          " · " state " (" (events/duration-str elapsed-ms) ")")
     ""]
    (when issue [(str "Issue:   #" issue (when issue-title (str "  " issue-title)))])
    (when pr [(str "PR:      #" pr)])
    (when phase [(str "Vaihe:   " phase)])
    (when agent [(str "Agentti: " (:role agent)
                      (when (:model agent) (str " (" (:model agent) ")"))
                      " · " (events/duration-str (:elapsed-ms agent)))])
    ["" "Viimeisimmät:"]
    (map #(str "  " (events/format-line %)) recent))))

(defn status-text [project-dir]
  (let [file (events/log-file project-dir)
        evs (events/last-run (events/read-events file))]
    (if (empty? evs)
      (str "Ei ajoja vielä. Loki: " file)
      (render (summarize evs (System/currentTimeMillis) events/pid-alive?)))))

(defn show [project-dir follow?]
  (if-not follow?
    (println (status-text project-dir))
    (loop []
      ;; Tyhjennä ruutu ja piirrä uudelleen kahden sekunnin välein. Ctrl-C lopettaa.
      (print (str "\u001b[H\u001b[2J" (status-text project-dir) "\n\n(päivittyy 2 s välein, Ctrl-C lopettaa)\n"))
      (flush)
      (Thread/sleep 2000)
      (recur))))
