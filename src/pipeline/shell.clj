(ns pipeline.shell
  "Komentojen ajaminen ja lokitus."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn log [& xs]
  (binding [*out* *err*]
    (println (str "[linjasto] " (str/join " " xs)))
    (flush)))

(defn sh
  "Ajaa komennon (vektori). Palauttaa {:exit :out :err} eikä heitä.
  Optiot: :dir, :in (stdin-merkkijono), :env (lisäympäristö),
  :err-inherit? (ohjaa stderrin suoraan konsoliin, hyödyllinen agenttiajoille)."
  [cmd & [{:keys [dir in env err-inherit?]}]]
  (let [res @(p/process (mapv str cmd)
                        (cond-> {:out :string
                                 :err (if err-inherit? :inherit :string)}
                          dir (assoc :dir (str dir))
                          ;; Stdin suljetaan aina, jottei mikään komento jää odottamaan syötettä.
                          true (assoc :in (or in ""))
                          env (assoc :extra-env env)))]
    {:exit (:exit res) :out (:out res) :err (:err res)}))

(defn sh!
  "Kuten `sh`, mutta heittää jos exit-koodi ei ole 0. Palauttaa trimmatun stdoutin."
  [cmd & [opts]]
  (let [{:keys [exit out err]} (sh cmd opts)]
    (when-not (zero? exit)
      (throw (ex-info (str "Komento epäonnistui (exit " exit "): "
                           (str/join " " (map str cmd))
                           (when-not (str/blank? err) (str "\n" (str/trim err))))
                      {:type ::command-failed :cmd cmd :exit exit :out out :err err})))
    (str/trim (or out ""))))

(defn json!
  "Ajaa komennon ja jäsentää stdoutin JSONina (avaimet keywordeina)."
  [cmd & [opts]]
  (let [out (sh! cmd opts)]
    (when-not (str/blank? out)
      (json/parse-string out true))))

(defn tail
  "Merkkijonon viimeiset n merkkiä."
  [s n]
  (let [s (or s "")]
    (if (> (count s) n) (str "…" (subs s (- (count s) n))) s)))
