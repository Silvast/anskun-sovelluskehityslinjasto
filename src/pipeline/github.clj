(ns pipeline.github
  "Ohuet kääreet gh-CLI:n ympärille. Kaikki tila luetaan ja kirjoitetaan GitHubiin."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [pipeline.shell :as shell])
  (:import (java.net URLEncoder)))

(defn- enc [s]
  (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn repo-name
  "owner/repo hakemiston git-remotesta."
  [dir]
  (shell/sh! ["gh" "repo" "view" "--json" "nameWithOwner" "-q" ".nameWithOwner"] {:dir dir}))

;; --- Issuet ---------------------------------------------------------------

(defn issue [repo n]
  (shell/json! ["gh" "issue" "view" n "--repo" repo
                "--json" "number,title,body,labels,state,url"]))

(defn issues-with-label [repo label]
  (shell/json! ["gh" "issue" "list" "--repo" repo "--label" label "--state" "open"
                "--limit" "200" "--json" "number,title,labels,createdAt"]))

(defn label-names [x]
  (set (map :name (:labels x))))

(defn add-labels! [repo n labels]
  (when (seq labels)
    (shell/sh! (into ["gh" "issue" "edit" n "--repo" repo]
                     (mapcat (fn [l] ["--add-label" l]) labels)))))

(defn remove-labels!
  "Poistaa labelit. Puuttuva label ei ole virhe."
  [repo n labels]
  (doseq [l labels]
    (shell/sh ["gh" "api" "-X" "DELETE"
               (str "repos/" repo "/issues/" n "/labels/" (enc l))])))

(defn set-labels!
  "Issueille ja PR:ille yhteinen: lisää `add`, poistaa `remove`."
  [repo n {:keys [add remove]}]
  (remove-labels! repo n remove)
  (add-labels! repo n add))

(defn comment! [repo n body]
  (shell/sh! ["gh" "issue" "comment" n "--repo" repo "--body-file" "-"] {:in body}))

(defn ensure-label! [repo label color description]
  (shell/sh! ["gh" "label" "create" label "--repo" repo "--force"
              "--color" color "--description" description]))

;; --- Pull requestit -------------------------------------------------------

(defn pr [repo n]
  (shell/json! ["gh" "pr" "view" n "--repo" repo
                "--json" "number,title,body,headRefName,baseRefName,state,labels,url,isDraft"]))

(defn open-pr-for-branch [repo branch]
  (first (shell/json! ["gh" "pr" "list" "--repo" repo "--head" branch "--state" "open"
                       "--json" "number,url,labels"])))

(defn open-pr-between
  "Avoin PR haarasta head haaraan base, tai nil."
  [repo head base]
  (first (shell/json! ["gh" "pr" "list" "--repo" repo "--head" head "--base" base
                       "--state" "open" "--json" "number,url,body"])))

(defn edit-pr-body! [repo n body]
  (shell/sh! ["gh" "pr" "edit" n "--repo" repo "--body-file" "-"] {:in body}))

(defn create-pr!
  "Luo PR:n ja palauttaa sen numeron."
  [repo {:keys [base head title body]}]
  (shell/sh! ["gh" "pr" "create" "--repo" repo "--base" base "--head" head
              "--title" title "--body-file" "-"]
             {:in body})
  (:number (open-pr-for-branch repo head)))

(defn pr-diff [repo n]
  (shell/sh! ["gh" "pr" "diff" n "--repo" repo]))

(defn review-bodies
  "Kaikkien PR:n katselmointien tekstit."
  [repo n]
  (->> (shell/json! ["gh" "api" "--paginate" "--slurp"
                     (str "repos/" repo "/pulls/" n "/reviews")])
       flatten
       (map :body)
       (remove nil?)
       vec))

(defn create-review!
  "Postaa COMMENT-tyyppisen katselmoinnin (omaa PR:ää ei voi hyväksyä eikä
  hylätä). Jos rivikommentit hylätään (rivi ei ole diffissä), postataan
  pelkkä runko."
  [repo n {:keys [body comments]}]
  (let [post (fn [payload]
               (shell/sh ["gh" "api" "-X" "POST" (str "repos/" repo "/pulls/" n "/reviews")
                          "--input" "-"]
                         {:in (json/generate-string payload)}))
        full (cond-> {:event "COMMENT" :body body}
               (seq comments) (assoc :comments comments))
        res (post full)]
    (if (zero? (:exit res))
      :ok
      (do (shell/log "Rivikommentit hylättiin, postataan pelkkä runko:" (str/trim (:err res)))
          (let [res2 (post {:event "COMMENT" :body body})]
            (when-not (zero? (:exit res2))
              (throw (ex-info "Katselmoinnin postaus epäonnistui" {:err (:err res2)})))
            :body-only)))))

(defn merge-pr!
  "Ottaa auto-mergen käyttöön (odottaa vaadittuja tarkistuksia). Jos repossa
  ei ole vaadittuja tarkistuksia, auto-merge ei käy ja PR mergetään suoraan."
  [repo n {:keys [method delete-branch]}]
  (let [base (cond-> ["gh" "pr" "merge" n "--repo" repo (str "--" method)]
               delete-branch (conj "--delete-branch"))
        auto (shell/sh (conj base "--auto"))]
    (if (zero? (:exit auto))
      :auto
      (do (shell/log "Auto-merge ei onnistunut, yritetään suoraa mergeä:" (str/trim (:err auto)))
          (shell/sh! base)
          :merged))))
