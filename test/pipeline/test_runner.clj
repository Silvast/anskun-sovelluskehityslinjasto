(ns pipeline.test-runner
  (:require [clojure.test :as t]
            [pipeline.core-test]))

(defn run []
  (let [{:keys [fail error]} (t/run-tests 'pipeline.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
