(ns pipeline.dashboard
  "`linjasto dashboard`: paikallinen HTTP-palvelin, joka näyttää kaikkien projektien
  ajot tapahtumalokista. Kuuntelee vain osoitetta 127.0.0.1."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [pipeline.config :as config]
            [pipeline.events :as events]
            [pipeline.status :as status])
  (:import (java.net URLDecoder)))

(defn run-summary [evs now-ms alive?]
  (let [start (first evs)]
    (-> (status/summarize evs now-ms alive?)
        (dissoc :recent)
        (assoc :run (:run start)
               :repo (:repo start)
               :tools (count (filter #(= "tool" (:type %)) evs))
               :errors (count (filter #(= "error" (:type %)) evs))))))

(defn project-state [project file now-ms alive?]
  (let [rs (events/runs (events/read-events file))]
    {:name project
     :runs (mapv #(run-summary % now-ms alive?) (reverse (take-last 40 rs)))}))

(defn state [now-ms alive?]
  {:now now-ms
   :projects (mapv (fn [[p f]] (project-state p f now-ms alive?)) (events/project-files))})

(defn run-events [project run-id]
  (when-let [f (get (events/project-files) project)]
    (->> (events/read-events f)
         (filter #(= run-id (:run %)))
         (take-last 1000)
         (mapv #(dissoc % :pid)))))

(defn- query-params [qs]
  (into {}
        (for [kv (str/split (or qs "") #"&")
              :let [[k v] (str/split kv #"=" 2)]
              :when (not (str/blank? k))]
          [k (URLDecoder/decode (or v "") "UTF-8")])))

(defn- json-response [data]
  {:status 200
   :headers {"Content-Type" "application/json; charset=utf-8"
             "Cache-Control" "no-store"}
   :body (json/generate-string data)})

(def page-file (str (fs/path config/pipeline-root "resources" "dashboard.html")))

(defn handler [{:keys [uri query-string request-method]}]
  (if (not= :get request-method)
    {:status 405 :body "Vain GET"}
    (case uri
      "/" {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
           ;; Luetaan joka kerta, jotta sivua voi muokata ilman uudelleenkäynnistystä.
           :body (slurp page-file)}
      "/api/state" (json-response (state (System/currentTimeMillis) events/pid-alive?))
      "/api/run" (let [{:strs [project run]} (query-params query-string)]
                   (json-response {:events (or (run-events project run) [])}))
      {:status 404 :body "Ei löydy"})))

(defn serve! [port]
  (http/run-server handler {:ip "127.0.0.1" :port port})
  (println (str "Linjaston dashboard: http://localhost:" port "  (Ctrl-C lopettaa)"))
  (println (str "Loki: " (events/state-dir)))
  @(promise))
