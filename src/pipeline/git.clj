(ns pipeline.git
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [pipeline.shell :as shell]))

(defn git! [dir & args]
  (shell/sh! (into ["git"] args) {:dir dir}))

(defn git [dir & args]
  (shell/sh (into ["git"] args) {:dir dir}))

(defn current-branch [dir]
  (git! dir "rev-parse" "--abbrev-ref" "HEAD"))

(defn prepare-worktree!
  "Luo puhtaan worktreen haaralle `branch`, joka alkaa kohdasta remote/base.
  Vanha samanniminen worktree poistetaan."
  [project-dir wt-path branch remote base]
  (git! project-dir "fetch" remote base)
  (when (fs/exists? wt-path)
    (git project-dir "worktree" "remove" "--force" (str wt-path)))
  (git project-dir "worktree" "prune")
  (fs/create-dirs (fs/parent wt-path))
  (git! project-dir "worktree" "add" "-B" branch (str wt-path) (str remote "/" base))
  (str wt-path))

(defn remove-worktree! [project-dir wt-path]
  (git project-dir "worktree" "remove" "--force" (str wt-path))
  (git project-dir "worktree" "prune"))

(defn commit-all!
  "Lisää kaikki muutokset ja committaa. Palauttaa false, jos muutoksia ei ollut."
  [dir message]
  (git! dir "add" "-A")
  (if (str/blank? (git! dir "diff" "--cached" "--name-only"))
    false
    (do (git! dir "commit" "-m" message) true)))

(defn changed-files
  "Tiedostot, jotka eroavat remote/base-haarasta."
  [dir remote base]
  (->> (str/split-lines (git! dir "diff" "--name-only" (str remote "/" base "...HEAD")))
       (remove str/blank?)
       vec))

(defn protected-violations
  "Palauttaa tiedostot, jotka osuvat suojattuihin polkuihin."
  [files protected-paths]
  (vec (filter (fn [f] (some #(str/starts-with? f %) protected-paths)) files)))

(defn push! [dir remote branch]
  (git! dir "push" "--force-with-lease" "-u" remote branch))
