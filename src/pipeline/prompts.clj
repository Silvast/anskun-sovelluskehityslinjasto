(ns pipeline.prompts
  "Promptipohjat: projektin .agent/prompts/<rooli>.md ylikirjoittaa linjaston oletuksen."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [pipeline.config :as config]))

(defn render
  "Korvaa {{avain}}-paikat arvoilla. Tuntematon avain jää ennalleen."
  [template vars]
  (str/replace template #"\{\{\s*([a-z0-9-]+)\s*\}\}"
               (fn [[m k]]
                 (let [v (get vars (keyword k) ::missing)]
                   (if (= v ::missing) m (str v))))))

(defn template-path [project-dir template-name]
  (let [override (fs/path project-dir ".agent" "prompts" (str template-name ".md"))]
    (if (fs/exists? override)
      override
      (fs/path config/pipeline-root "prompts" (str template-name ".md")))))

(defn prompt [project-dir template-name vars]
  (render (slurp (str (template-path project-dir template-name))) vars))
