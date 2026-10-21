(ns pipeline.cli
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [pipeline.config :as config]
            [pipeline.dashboard :as dashboard]
            [pipeline.events :as events]
            [pipeline.github :as gh]
            [pipeline.implement :as implement]
            [pipeline.queue :as queue]
            [pipeline.review :as review]
            [pipeline.shell :as shell]
            [pipeline.status :as status]))

(def usage
  "anskun-sovelluskehityslinjasto

Komennot (ajetaan projektirepon hakemistossa tai --project <hakemisto>):

  setup-labels          Luo linjaston labelit projektirepoon
  next                  Näytä prioriteettijono
  run-issue <n>         Toteuta issue n omalla koneella ja avaa PR
  run-next              Ota jonosta enintään :max-issues-per-run issueta, toteuta,
                        katselmoi ja mergeä developiin (lukittu)
  review-round <pr>     Yksi katselmointikierros (Actions)
  review-loop <pr>      Kierroksia kunnes valmis (paikallinen testaus)
  mark-merged <pr>      Merkitse PR:n issue developissa olevaksi
  sync                  Päivitä auto-mergellä mergetyt PR:t release-PR:ään
  config                Tulosta yhdistetty konfiguraatio
  status [-f]           Viimeisimmän ajon tila; -f päivittää jatkuvasti
  log                   Lokitiedoston polku (esim. tail -f $(linjasto log))
  dashboard [--port n]  Paikallinen selainnäkymä kaikista projekteista (oletus 8787)

Yleiset liput:

  --project <hakemisto> Projektirepo (oletus: nykyinen hakemisto)
  --model <malli>       Implementerin malli tälle ajolle
  --model <rooli>=<malli>
                        Minkä tahansa roolin malli: implementer, reviewer-a,
                        reviewer-b tai fixer. Lipun voi antaa useasti.

Esimerkki:
  linjasto run-issue 13 --model opencode-go/minimax-m3
  linjasto review-loop 7 --model reviewer-b=sonnet")

(defn parse-args [args]
  (loop [[a & more :as xs] args, opts {}, pos []]
    (cond
      (empty? xs) (assoc opts :args pos)
      (= a "--project") (recur (rest more) (assoc opts :project (first more)) pos)
      (= a "--model") (recur (rest more) (update opts :models (fnil conj []) (first more)) pos)
      (#{"-f" "--follow"} a) (recur more (assoc opts :follow true) pos)
      (= a "--port") (recur (rest more) (assoc opts :port (parse-long (str (first more)))) pos)
      :else (recur more opts (conj pos a)))))

(def roles #{"implementer" "reviewer-a" "reviewer-b" "fixer"})

(defn parse-model-override
  "\"malli\" → [:implementer \"malli\"], \"fixer=sonnet\" → [:fixer \"sonnet\"]."
  [s]
  (when (str/blank? s)
    (throw (ex-info "--model tarvitsee arvon, esim. --model opencode-go/minimax-m3" {})))
  (let [[role model] (str/split s #"=" 2)]
    (if (and model (roles role))
      [(keyword role) model]
      [:implementer s])))

(defn apply-model-overrides [cfg models]
  (reduce (fn [cfg s]
            (let [[role model] (parse-model-override s)]
              (assoc-in cfg [:roles role :model] model)))
          cfg
          models))

(defn context [{:keys [project models]}]
  (let [dir (str (fs/canonicalize (or project ".")))
        cfg (apply-model-overrides (config/load-config dir) models)]
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

(defn- process-issue
  "Toteuttaa issuen ja, jos asetus on päällä, katselmoi ja mergeää sen PR:n."
  [{:keys [cfg] :as ctx} n]
  (let [res (implement/run-issue ctx n)]
    (if (and (= :pr-opened (:status res)) (:review-in-run-next cfg))
      (assoc res :review (:status (review/review-loop ctx (:pr res))))
      res)))

(defn run-next
  "Orkestroijan yksi kierros: siivoa mergetyt, käy jono läpi, siivoa uudelleen."
  [{:keys [cfg project-dir] :as ctx}]
  (with-lock (str (fs/path (fs/temp-dir) (str "linjasto-" (fs/file-name project-dir) ".lock")))
    (fn []
      (review/sweep-merged ctx)
      (let [done (loop [done [], left (:max-issues-per-run cfg)]
                   (let [issue (when (pos? left) (queue/next-issue cfg (:repo ctx)))]
                     (if-not issue
                       done
                       (recur (conj done (process-issue ctx (:number issue))) (dec left)))))]
        (review/sweep-merged ctx)
        (events/phase! (str "kierros valmis: " (count done) " issueta, "
                            (count (filter #(= :clean (:review %)) done)) " developiin, "
                            (count (filter #(= :approved (:review %)) done)) " odottaa mergeäsi, "
                            (count (filter #(or (= :needs-human (:status %))
                                                (= :needs-human (:review %))) done))
                            " odottaa sinua"))
        done))))

(defn setup-labels [{:keys [cfg repo]}]
  (let [l (:labels cfg)]
    (doseq [[k color desc] [[:ready "1d5fc4" "The pipeline may pick this up"]
                            [:working "e9b44c" "Implementer is working"]
                            [:review "e9b44c" "Review loop running"]
                            [:needs-human "c23b31" "Pipeline stopped, over to a human"]
                            [:merging "23864f" "Waiting for auto-merge into develop"]
                            [:in-develop "23864f" "Merged into develop"]
                            [:hold "7a4fd0" "Review, but let me merge into develop"]
                            [:approved "0f8577" "Reviewed, waiting for a human merge into develop"]
                            [:released "5b6977" "Release published"]]]
      (gh/ensure-label! repo (get l k) color desc)
      (shell/log "Label" (get l k)))))

(defn- result-status [res]
  (cond (map? res) (some-> (:status res) name)
        (sequential? res) (str (count res) " issueta")
        :else "ok"))

(defn- tracked
  "Ajaa komennon tapahtumalokin kanssa: run-start, vaiheet, run-end."
  [opts command target f]
  (let [ctx (context opts)]
    (binding [events/*ctx* (events/start-run! (:project-dir ctx) command target (:repo ctx))]
      (let [res (try (f ctx)
                     (catch Exception e
                       (events/emit! :run-end {:status "virhe"})
                       (throw e)))]
        (events/emit! :run-end {:status (result-status res)})
        res))))

(defn -main [& raw]
  (let [[cmd & rest-args] raw
        {:keys [args] :as opts} (parse-args rest-args)
        project-dir #(str (fs/canonicalize (or (:project opts) ".")))]
    (try
      (let [result
            (case cmd
              ("help" "--help" nil) (println usage)
              "config" (let [{:keys [cfg repo]} (context opts)]
                         (pp/pprint (assoc cfg :repo repo)))
              "status" (status/show (project-dir) (:follow opts))
              "log" (println (events/log-file (project-dir)))
              "dashboard" (dashboard/serve! (or (:port opts) 8787))
              "setup-labels" (setup-labels (context opts))
              "next" (doseq [i (queue/ready-issues (:cfg (context opts)) (:repo (context opts)))]
                       (println (str "#" (:number i) "  " (:title i))))
              "run-issue" (let [n (need-number args)]
                            (tracked opts cmd (str "#" n) #(implement/run-issue % n)))
              "run-next" (tracked opts cmd nil run-next)
              "review-round" (let [n (need-number args)]
                               (tracked opts cmd (str "PR #" n) #(review/review-round % n)))
              "review-loop" (let [n (need-number args)]
                              (tracked opts cmd (str "PR #" n) #(review/review-loop % n)))
              "sync" (tracked opts cmd nil review/sweep-merged)
              "mark-merged" (let [n (need-number args)]
                              (tracked opts cmd (str "PR #" n) #(review/mark-merged % n)))
              (do (println usage) (throw (ex-info (str "Tuntematon komento: " cmd) {}))))]
        (when (and (map? result) (= :needs-human (:status result)))
          (System/exit 2)))
      (catch Exception e
        (shell/log "Virhe:" (ex-message e))
        (System/exit 1)))))
