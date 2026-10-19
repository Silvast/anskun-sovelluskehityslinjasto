(ns pipeline.cli
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [pipeline.config :as config]
            [pipeline.github :as gh]
            [pipeline.implement :as implement]
            [pipeline.queue :as queue]
            [pipeline.review :as review]
            [pipeline.shell :as shell]))

(def usage
  "anskun-sovelluskehityslinjasto

Komennot (ajetaan projektirepon hakemistossa tai --project <hakemisto>):

  setup-labels          Luo linjaston labelit projektirepoon
  next                  Näytä prioriteettijono
  run-issue <n>         Toteuta issue n omalla koneella ja avaa PR
  run-next              Ota jonosta enintään :max-issues-per-run issueta (lukittu)
  review-round <pr>     Yksi katselmointikierros (Actions)
  review-loop <pr>      Kierroksia kunnes valmis (paikallinen testaus)
  mark-merged <pr>      Merkitse PR:n issue developissa olevaksi
  config                Tulosta yhdistetty konfiguraatio")

(defn parse-args [args]
  (loop [[a & more :as xs] args, opts {}, pos []]
    (cond
      (empty? xs) (assoc opts :args pos)
      (= a "--project") (recur (rest more) (assoc opts :project (first more)) pos)
      :else (recur more opts (conj pos a)))))

(defn context [{:keys [project]}]
  (let [dir (str (fs/absolutize (or project ".")))
        cfg (config/load-config dir)]
    {:cfg cfg
     :project-dir dir
     :repo (or (:repo cfg) (gh/repo-name dir))}))

(def stale-lock-ms (* 6 60 60 1000))

(defn- acquire-lock! [path]
  (try (fs/create-file path) true
       (catch java.nio.file.FileAlreadyExistsException _
         (let [age (- (System/currentTimeMillis)
                      (.toMillis (fs/last-modified-time path)))]
           (when (> age stale-lock-ms)
             (shell/log "Poistetaan vanhentunut lukko" (str path))
             (fs/delete-if-exists path)
             (try (fs/create-file path) true
                  (catch java.nio.file.FileAlreadyExistsException _ false)))))))

(defn with-lock
  "Ajaa f:n vain jos lukkotiedosto saadaan luotua; muuten palauttaa nil.
  Yli 6 tuntia vanha lukko tulkitaan kaatuneen ajon jäänteeksi."
  [path f]
  (if (acquire-lock! path)
    (try (f) (finally (fs/delete-if-exists path)))
    (do (shell/log "Toinen ajo on käynnissä, lopetetaan.") nil)))

(defn- need-number [args]
  (or (some-> (first args) parse-long)
      (throw (ex-info "Anna numero, esim. `bb run-issue 42`." {}))))

(defn run-next [{:keys [cfg project-dir] :as ctx}]
  (with-lock (str (fs/path (fs/temp-dir) (str "linjasto-" (fs/file-name project-dir) ".lock")))
    (fn []
      (loop [done [], left (:max-issues-per-run cfg)]
        (let [issue (when (pos? left) (queue/next-issue cfg (:repo ctx)))]
          (if-not issue
            (do (shell/log "Valmis:" (count done) "issueta käsitelty.") done)
            (recur (conj done (implement/run-issue ctx (:number issue))) (dec left))))))))

(defn setup-labels [{:keys [cfg repo]}]
  (let [l (:labels cfg)]
    (doseq [[k color desc] [[:ready "1d5fc4" "Linjasto saa ottaa tämän työn alle"]
                            [:working "e9b44c" "Implementer työskentelee"]
                            [:review "e9b44c" "Review-silmukka käynnissä"]
                            [:needs-human "c23b31" "Linjasto pysähtyi, ihmisen vuoro"]
                            [:merging "23864f" "Auto-merge developiin odottaa"]
                            [:in-develop "23864f" "Mergetty developiin"]]]
      (gh/ensure-label! repo (get l k) color desc)
      (shell/log "Label" (get l k)))))

(defn -main [& raw]
  (let [[cmd & rest-args] raw
        {:keys [args] :as opts} (parse-args rest-args)]
    (try
      (let [result
            (case cmd
              ("help" "--help" nil) (println usage)
              "config" (pp/pprint (:cfg (context opts)))
              "setup-labels" (setup-labels (context opts))
              "next" (doseq [i (queue/ready-issues (:cfg (context opts)) (:repo (context opts)))]
                       (println (str "#" (:number i) "  " (:title i))))
              "run-issue" (implement/run-issue (context opts) (need-number args))
              "run-next" (run-next (context opts))
              "review-round" (review/review-round (context opts) (need-number args))
              "review-loop" (review/review-loop (context opts) (need-number args))
              "mark-merged" (review/mark-merged (context opts) (need-number args))
              (do (println usage) (throw (ex-info (str "Tuntematon komento: " cmd) {}))))]
        (when (map? result) (shell/log (pr-str result)))
        (when (and (map? result) (= :needs-human (:status result)))
          (System/exit 2)))
      (catch clojure.lang.ExceptionInfo e
        (shell/log "Virhe:" (ex-message e))
        (System/exit 1)))))
