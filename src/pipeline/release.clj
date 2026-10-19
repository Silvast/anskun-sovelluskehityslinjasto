(ns pipeline.release
  "Release-PR develop → main. Linjasto pitää auki yhtä PR:ää, jonka kuvaukseen
  lisätään rivi jokaisesta developiin mergetystä issuesta. Rivit ovat muotoa
  `Closes #n`, joten GitHub sulkee issuet, kun release-PR mergetään mainiin."
  (:require [clojure.string :as str]
            [pipeline.events :as events]
            [pipeline.github :as gh]
            [pipeline.shell :as shell]))

(def marker "<!-- linjasto:release -->")

(defn initial-body [base-branch main-branch]
  (str "Kokoaa `" base-branch "`-haaraan mergetyt muutokset `" main-branch "`-haaraan.\n"
       "Linjasto päivittää listaa automaattisesti. Kun tämä PR mergetään, listan issuet sulkeutuvat.\n\n"
       marker "\n"))

(defn release-line [{:keys [issue pr title]}]
  (str "- Closes #" issue
       (when pr (str " · PR #" pr))
       (when-not (str/blank? title) (str " · " title))))

(defn includes-issue? [body issue-n]
  (boolean (re-find (re-pattern (str "(?m)^- Closes #" issue-n "\\b")) (or body ""))))

(defn add-line
  "Lisää rivin kuvauksen loppuun, ellei issue ole jo listalla."
  [body entry]
  (let [body (if (str/includes? (or body "") marker) body (str body "\n\n" marker "\n"))]
    (if (includes-issue? body (:issue entry))
      body
      (str (str/trimr body) "\n" (release-line entry) "\n"))))

(defn ensure-release-pr!
  "Luo release-PR:n, jos sitä ei ole, ja lisää siihen issuen. Palauttaa PR:n numeron
  tai nil, jos PR:ää ei voitu luoda (esim. develop ei eroa mainista)."
  [{:keys [cfg repo]} entry]
  (let [{:keys [main-branch title]} (:release cfg)
        base (:base-branch cfg)
        existing (gh/open-pr-between repo base main-branch)]
    (if existing
      (let [body (add-line (:body existing) entry)]
        (when-not (= body (:body existing))
          (gh/edit-pr-body! repo (:number existing) body)
          (events/phase! (str "release-PR #" (:number existing) ": lisätty issue #" (:issue entry))))
        (:number existing))
      (try
        (let [n (gh/create-pr! repo {:base main-branch :head base :title title
                                     :body (add-line (initial-body base main-branch) entry)})]
          (events/phase! (str "release-PR #" n " luotu (" base " → " main-branch ")"))
          n)
        (catch Exception e
          (shell/log "Release-PR:ää ei voitu luoda:" (ex-message e))
          nil)))))
