(ns pipeline.queue
  "Prioriteettijono: mikä agent:ready-issue otetaan seuraavaksi."
  (:require [clojure.string :as str]
            [pipeline.github :as gh]
            [pipeline.shell :as shell]))

(defn- index-of [coll x]
  (let [i (.indexOf ^java.util.List (vec coll) x)]
    (when-not (neg? i) i)))

(defn label-priority
  "Pienin indeksi priority-labeleista, joita issuella on. Ilman labelia jonon häntään."
  [priority-labels issue]
  (let [names (gh/label-names issue)]
    (or (first (keep-indexed (fn [i l] (when (names l) i)) priority-labels))
        (count priority-labels))))

(defn sort-by-labels
  "Järjestää prioriteetti-labelin mukaan, tasatilanteessa vanhin issue ensin."
  [priority-labels issues]
  (sort-by (juxt #(label-priority priority-labels %) :number) issues))

(defn sort-project-items
  "GitHub Projectin itemit: kentän arvon järjestys :order-listan mukaan.
  `gh project item-list --format json` palauttaa kenttien arvot pienaakkosin
  nimetyillä avaimilla (esim. :priority)."
  [{:keys [field order]} items]
  (let [k (keyword (str/lower-case field))]
    (sort-by (juxt #(or (index-of order (get % k)) (count order))
                   #(get-in % [:content :number]))
             items)))

(defn- from-labels [cfg repo]
  (let [ready (get-in cfg [:labels :ready])]
    (sort-by-labels (get-in cfg [:queue :priority-labels])
                    (gh/issues-with-label repo ready))))

(defn- from-project [cfg repo]
  (let [{:keys [owner number] :as project} (get-in cfg [:queue :project])
        ready (get-in cfg [:labels :ready])
        items (:items (shell/json! ["gh" "project" "item-list" number "--owner" owner
                                    "--format" "json" "--limit" "500"]))]
    (->> items
         (filter #(= "Issue" (get-in % [:content :type])))
         (filter #(= repo (get-in % [:content :repository])))
         (filter #(some #{ready} (:labels %)))
         (sort-project-items project)
         (map (fn [it] {:number (get-in it [:content :number])
                        :title (get-in it [:content :title])})))))

(defn ready-issues [cfg repo]
  (case (get-in cfg [:queue :source])
    :project (from-project cfg repo)
    (from-labels cfg repo)))

(defn next-issue [cfg repo]
  (first (ready-issues cfg repo)))
