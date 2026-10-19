(ns pipeline.events
  "Tapahtumaloki. Orkestroija kirjaa jokaisen vaiheen ja agentin työkalukutsun
  JSONL-tiedostoon ja tulostaa saman ihmisluettavana terminaaliin.

  Tiedosto: $LINJASTO_STATE_DIR tai $XDG_STATE_HOME/linjasto tai
  ~/.local/state/linjasto, nimellä <projektin hakemiston nimi>.jsonl."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time Instant LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:dynamic *ctx*
  "Ajon konteksti, joka liitetään jokaiseen tapahtumaan: :run :project :log-file
  sekä vaiheesta riippuen :issue :pr :role."
  {})

(def max-log-bytes (* 5 1024 1024))

(defn state-dir []
  (str (or (System/getenv "LINJASTO_STATE_DIR")
           (some-> (System/getenv "XDG_STATE_HOME") (fs/path "linjasto"))
           (fs/path (fs/home) ".local" "state" "linjasto"))))

(defn log-file [project-dir]
  (str (fs/path (state-dir) (str (fs/file-name (fs/canonicalize project-dir)) ".jsonl"))))

(defn- rotate! [file]
  (when (and (fs/exists? file) (> (fs/size file) max-log-bytes))
    (fs/move file (str file ".1") {:replace-existing true})))

;; --- Muotoilu ----------------------------------------------------------------

(def ^:private hhmmss (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn clock [ts]
  (try (.format hhmmss (LocalDateTime/ofInstant (Instant/parse ts) (ZoneId/systemDefault)))
       (catch Exception _ "--:--:--")))

(defn duration-str [ms]
  (let [s (quot (or ms 0) 1000)]
    (cond (< s 60) (str s " s")
          (< s 3600) (str (quot s 60) " min " (mod s 60) " s")
          :else (str (quot s 3600) " h " (quot (mod s 3600) 60) " min"))))

(defn describe
  "Tapahtuma ihmisluettavaksi riviksi ilman aikaleimaa."
  [{:keys [type role msg tool detail model ms ok status] :as e}]
  (let [who (when role (str "[" (name role) "] "))]
    (case type
      "run-start" (str "▶ " (:command e) (when-let [t (:target e)] (str " " t)))
      "run-end" (str "■ valmis: " status)
      "phase" (str who msg)
      "agent-start" (str who "aloittaa" (when model (str " (" model ")")))
      "tool" (str who (str/trim (str tool " " (or detail ""))))
      "agent-text" (str who "💬 " msg)
      "agent-end" (str who (if ok "valmis" "epäonnistui") " · " (duration-str ms))
      "error" (str who "⚠ " msg)
      (str who type (when msg (str " " msg))))))

(defn format-line [e]
  (str (clock (:ts e)) " " (describe e)))

;; --- Kirjaus -----------------------------------------------------------------

(defn emit!
  "Kirjaa tapahtuman lokitiedostoon ja tulostaa sen stderriin."
  [type m]
  (let [e (merge (select-keys *ctx* [:run :project :issue :pr :role])
                 m
                 {:type (name type) :ts (str (Instant/now))})]
    (binding [*out* *err*]
      (println (str "[linjasto] " (format-line e)))
      (flush))
    (when-let [f (:log-file *ctx*)]
      (try
        (fs/create-dirs (fs/parent f))
        (spit f (str (json/generate-string e) "\n") :append true)
        (catch Exception _ nil)))
    e))

(defn phase! [msg & {:as extra}]
  (emit! :phase (assoc extra :msg msg)))

(defn start-run!
  "Palauttaa kontekstin uudelle ajolle. Käytä: (binding [*ctx* (start-run! …)] …)."
  [project-dir command target]
  (let [f (log-file project-dir)
        ctx {:run (str (System/currentTimeMillis))
             :project (str (fs/file-name (fs/canonicalize project-dir)))
             :log-file f}]
    (try (fs/create-dirs (fs/parent f)) (rotate! f) (catch Exception _ nil))
    (binding [*ctx* ctx]
      (emit! :run-start {:command command :target target
                         :pid (.pid (java.lang.ProcessHandle/current))}))
    ctx))

;; --- Lukeminen ---------------------------------------------------------------

(defn read-events [file]
  (if (fs/exists? file)
    (->> (str/split-lines (slurp file))
         (keep #(try (json/parse-string % true) (catch Exception _ nil)))
         vec)
    []))

(defn last-run
  "Viimeisimmän ajon tapahtumat."
  [events]
  (let [run-id (:run (last (filter #(= "run-start" (:type %)) events)))]
    (filterv #(= run-id (:run %)) events)))

(defn pid-alive? [pid]
  (boolean (some-> pid long java.lang.ProcessHandle/of (.orElse nil) .isAlive)))
