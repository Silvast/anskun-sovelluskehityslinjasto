(ns pipeline.project
  "Valinnainen GitHub Projectin Status-synkka: kun linjasto lisää issueen labelin,
  issuen kortti siirtyy vastaavaan sarakkeeseen. Käyttää gh project -komentoja
  (GraphQL), joten gh tarvitsee project-oikeuden: gh auth refresh -s project."
  (:require [clojure.string :as str]
            [pipeline.events :as events]
            [pipeline.shell :as shell]))

(defn settings
  "Synkan asetukset, tai nil jos synkka ei ole päällä.
  Projekti otetaan :project-statusista tai jonon :project-asetuksesta."
  [cfg]
  (let [ps (:project-status cfg)
        qp (get-in cfg [:queue :project])
        owner (or (:owner ps) (:owner qp))
        number (or (:number ps) (:number qp))]
    (when (and ps (not (false? (:enabled ps))) owner number)
      (assoc ps :owner owner :number number))))

(defn column-for
  "Sarake, johon kortti siirretään, kun issueen lisätään nämä labelit. Viimeinen osuma voittaa."
  [cfg added-labels]
  (let [by-name (into {} (map (fn [[k v]] [v k]) (:labels cfg)))
        columns (get-in cfg [:project-status :columns])]
    (last (keep #(get columns (by-name %)) added-labels))))

(defonce ^:private cache (atom {}))

(defn- project-meta
  "Projektin id sekä Status-kentän id ja vaihtoehdot. Haetaan kerran per ajo."
  [{:keys [owner number field]}]
  (or (get @cache [owner number field])
      (let [n (str number)
            pid (:id (shell/json! ["gh" "project" "view" n "--owner" owner "--format" "json"]))
            fields (:fields (shell/json! ["gh" "project" "field-list" n "--owner" owner
                                          "--format" "json" "--limit" "100"]))
            f (first (filter #(= (str/lower-case field) (str/lower-case (:name %))) fields))
            m {:project-id pid
               :field-id (:id f)
               :options (into {} (map (juxt (comp str/lower-case :name) :id) (:options f)))}]
        (when-not (:id f)
          (throw (ex-info (str "Projektissa ei ole kenttää " field) {})))
        (swap! cache assoc [owner number field] m)
        m)))

(defn- pull-request? [repo n]
  (boolean (:pull_request (shell/json! ["gh" "api" (str "repos/" repo "/issues/" n)]))))

(defn- item-id
  "Issuen kortin id projektissa. Jos issue ei ole projektissa, se lisätään.
  PR:t ohitetaan (nil): linjasto lisää labelit sekä issueen että sen PR:ään."
  [{:keys [owner number]} repo n]
  (let [items (:items (shell/json! ["gh" "project" "item-list" (str number) "--owner" owner
                                    "--format" "json" "--limit" "1000"]))
        it (first (filter #(and (= "Issue" (get-in % [:content :type]))
                                (= repo (get-in % [:content :repository]))
                                (= n (get-in % [:content :number])))
                          items))]
    (cond
      (:id it) (:id it)
      (pull-request? repo n) nil
      :else
        (:id (shell/json! ["gh" "project" "item-add" (str number) "--owner" owner
                           "--url" (str "https://github.com/" repo "/issues/" n)
                           "--format" "json"])))))

(defn move!
  "Siirtää issuen n kortin sarakkeeseen. Virhe ei pysäytä linjastoa, vaan kirjataan lokiin."
  [cfg repo n column]
  (when-let [s (settings cfg)]
    (try
      (let [{:keys [project-id field-id options]} (project-meta s)
            option (get options (str/lower-case column))]
        (if-not option
          (events/phase! (str "⚠️ projektissa ei ole saraketta " column ", korttia ei siirretty"))
          (when-let [id (item-id s repo n)]
            (shell/sh! ["gh" "project" "item-edit" "--project-id" project-id "--id" id
                        "--field-id" field-id "--single-select-option-id" option])
            (events/phase! (str "projekti: #" n " → " column)))))
      (catch Exception e
        (events/phase! (str "⚠️ projektin sarakkeen päivitys epäonnistui: "
                            (first (str/split-lines (str (ex-message e))))))))))

(defn label-hook
  "Funktio github/*label-hook*:iin: siirtää kortin, kun linjasto lisää labelin issueen."
  [cfg]
  (when (settings cfg)
    (fn [repo n added]
      (when-let [column (column-for cfg added)]
        (move! cfg repo n column)))))
