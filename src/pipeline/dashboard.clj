(ns pipeline.dashboard
  "`linjasto dashboard`: paikallinen HTTP-palvelin, joka näyttää kaikkien projektien
  ajot tapahtumalokista. Kuuntelee vain osoitetta 127.0.0.1."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [pipeline.config :as config]
            [pipeline.events :as events]
            [pipeline.github :as gh]
            [pipeline.queue :as queue]
            [pipeline.release :as release]
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

;; --- Jono ---------------------------------------------------------------------
;; Jono haetaan GitHubista projektin omalla konfiguraatiolla. Haku on hidas, joten
;; tulos välimuistitetaan ja päivitetään taustalla korkeintaan minuutin välein.

(def queue-ttl-ms 60000)
(defonce queue-cache (atom {}))

(defn- fetch-queue [project-dir repo]
  (try
    {:items (mapv #(select-keys % [:number :title])
                  (queue/ready-issues (config/load-config project-dir) repo))}
    (catch Exception e
      {:error (first (str/split-lines (str (ex-message e))))})))

(defn queue-state
  "Palauttaa välimuistissa olevan jonon heti ja käynnistää taustahaun, jos se on vanha."
  [project project-dir repo now-ms]
  (let [{:keys [at loading] :as cached} (get @queue-cache project)]
    (when (and project-dir repo (not loading)
               (or (nil? at) (> (- now-ms at) queue-ttl-ms)))
      (swap! queue-cache assoc-in [project :loading] true)
      (future
        (let [res (fetch-queue project-dir repo)]
          (swap! queue-cache assoc project (assoc res :at (System/currentTimeMillis) :loading false)))))
    (cond
      (not (and project-dir repo)) {:error "Jono näkyy, kun projektissa on ajettu linjastoa tämän version jälkeen."}
      (nil? at) {:loading true}
      :else (dissoc cached :loading))))

;; --- Odottaa sinua ----------------------------------------------------------

(defn release-items
  "Release-PR:n kuvauksen Closes-rivit: [{:issue 13 :pr 14 :title \"…\"}]."
  [body]
  (vec (for [[_ issue pr title] (re-seq #"(?m)^- Closes #(\d+)(?: · PR #(\d+))?(?: · (.*))?$" (or body ""))]
         {:issue (parse-long issue)
          :pr (some-> pr parse-long)
          :title (some-> title str/trim)})))

(defn stop-reason
  "Linjaston pysähdyskommentista lyhyt syy, tai nil."
  [body]
  (when (and body (str/starts-with? body "🛑"))
    (let [fenced (second (re-find #"(?s)```\n?(.*?)```" body))
          text (or (some->> fenced str/split-lines (remove str/blank?) first)
                   (some-> (re-find #"pysähtyi:\s*(.*)" body) second))]
      (some-> text str/trim (#(if (> (count %) 220) (str (subs % 0 219) "…") %))))))

(defn- fetch-attention [project-dir repo]
  (try
    (let [cfg (config/load-config project-dir)
          rel (gh/open-pr-between repo (:base-branch cfg) (get-in cfg [:release :main-branch]))
          stuck (gh/open-items-with-label repo (get-in cfg [:labels :needs-human]))]
      {:release (when rel {:number (:number rel) :url (:url rel)
                           :items (release-items (:body rel))})
       :stuck (mapv (fn [it]
                      (assoc it :reason (some stop-reason (reverse (gh/comment-bodies repo (:number it))))))
                    stuck)})
    (catch Exception e
      {:error (first (str/split-lines (str (ex-message e))))})))

(defonce attention-cache (atom {}))

(defn attention-state [project project-dir repo now-ms]
  (let [{:keys [at loading] :as cached} (get @attention-cache project)]
    (when (and project-dir repo (not loading)
               (or (nil? at) (> (- now-ms at) queue-ttl-ms)))
      (swap! attention-cache assoc-in [project :loading] true)
      (future
        (let [res (fetch-attention project-dir repo)]
          (swap! attention-cache assoc project (assoc res :at (System/currentTimeMillis) :loading false)))))
    (cond
      (not (and project-dir repo)) {:error "Näkyy, kun projektissa on ajettu linjastoa tämän version jälkeen."}
      (nil? at) {:loading true}
      :else (dissoc cached :loading))))

(defn project-state [project file now-ms alive?]
  (let [rs (events/runs (events/read-events file))
        latest-start (last (keep #(when (:project-dir (first %)) (first %)) rs))]
    {:name project
     :queue (queue-state project (:project-dir latest-start) (:repo latest-start) now-ms)
     :attention (attention-state project (:project-dir latest-start) (:repo latest-start) now-ms)
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
