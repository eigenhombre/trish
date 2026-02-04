(ns trish.fetch
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [trish.time :as time]))

(defn gh-token []
  (or (System/getenv "GH_PERS_PAT")
      (throw (ex-info "Need GH_PERS_PAT env var" {}))))

(defn- default-headers [token]
  {"Authorization" (str "Bearer " token)
   "Accept" "application/vnd.github+json"
   "Content-Type" "application/json"})

(defn build-url
  "
  Build a GitHub API URL with query parameters.
  "
  [base-path params]
  (str "https://api.github.com" base-path
       (when (seq params)
         (str "?" (clojure.string/join
                   "&"
                   (map (fn [[k v]]
                          (str k "=" v))
                        params))))))

(defn github-issues-url
  "
  Build a URL for fetching issues from a GitHub repository.
  "
  [repo & {:keys [author assignee page state]
           :or {page 1 state "all"}}]
  (let [state (or state "all")
        params (cond-> [["per_page" "100"]
                        ["state" state]
                        ["page" (str page)]
                        ["since" (time/iso8601-days-ago 100)]]
                 author (conj ["author" author])
                 assignee (conj ["assignee" assignee]))]
    (build-url (str "/repos/" repo "/issues") params)))

(defn fetch-issues
  "
  Fetch issues from a GitHub repository.
  "
  [repo & {:keys [page state assignee verbose]
           :or {page 1}}]
  (let [token (gh-token)
        url (github-issues-url repo
                               :page page
                               :state state
                               :assignee assignee)]
    (when verbose
      (println "GET" url))
    (-> (http/get url
                  ;; TODO: use default-headers:
                  {:headers {"Authorization" (str "Bearer " token)
                             "Accept" "application/vnd.github+json"}
                   :as :json})
        :body)))

(defn issue-labels-url
  "
  Build a URL for managing labels on an issue.
  "
  [repo issue-number & [label]]
  (if label
    (str "https://api.github.com/repos/" repo "/issues/"
         issue-number "/labels/" label)
    (str "https://api.github.com/repos/" repo "/issues/"
         issue-number "/labels")))

(defn add-issue-labels
  "
  Add labels to a GitHub issue.
  "
  [repo issue-number labels & {:keys [verbose]}]
  (let [token (gh-token)
        url (issue-labels-url repo issue-number)
        body (json/generate-string labels)]
    (when verbose
      (println "POST" url "<-" body))
    (http/post url
               {:headers (default-headers token)
                :body body})))

(defn remove-issue-label
  "
  Remove a label from an issue, tolerating cases where the label
  doesn't exist.
  "
  [repo issue-number label & {:keys [verbose]}]
  (let [token (gh-token)
        url (issue-labels-url repo issue-number label)]
    (when verbose
      (println "DELETE" url))
    (try
      (http/delete url
                   ;; TODO: default-headers
                   {:headers {"Authorization" (str "Bearer " token)
                              "Accept" "application/vnd.github+json"}})
      (catch Exception e
        (when (= 404 (:status (ex-data e)))
          (when verbose
            (println "Label" label "not present on issue"
                     repo "/" issue-number "(ignoring)"))
          nil)
        (when-not (= 404 (:status (ex-data e)))
          (throw e))))))

(defn issue-assignees-url
  "
  Build a URL for managing assignees on an issue.
  "
  [repo issue-number]
  (str "https://api.github.com/repos/" repo "/issues/"
       issue-number "/assignees"))

(defn set-issue-assignee
  "
  Assign a user to a GitHub issue.
  "
  [repo issue-number assignee & {:keys [verbose]}]
  (let [token (gh-token)
        url (issue-assignees-url repo issue-number)
        body (json/generate-string {:assignees [assignee]})]
    (when verbose
      (println "POST" url "<-" body))
    (http/post url
               {:headers (default-headers token)
                :body body})))

(defn remove-issue-assignee
  "
  Remove an assignee from an issue, tolerating cases where the
  assignee isn't assigned.
  "
  [repo issue-number assignee & {:keys [verbose]}]
  (let [token (gh-token)
        url (issue-assignees-url repo issue-number)
        body (json/generate-string {:assignees [assignee]})]
    (when verbose
      (println "DELETE" url "<-" body))
    (try
      (http/delete url
                   {:headers (default-headers token)
                    :body body})
      (catch Exception e
        (when (= 404 (:status (ex-data e)))
          (when verbose
            (println "Assignee" assignee "not present on issue"
                     repo "/" issue-number "(ignoring)"))
          nil)
        (when-not (= 404 (:status (ex-data e)))
          (throw e))))))

(defn set-issue-state
  "
  Set the state of a GitHub issue (open or closed).
  "
  [repo issue-number state & {:keys [verbose]}]
  (let [token (gh-token)
        url (str "https://api.github.com/repos/" repo "/issues/"
                 issue-number)
        body (json/generate-string {:state state})]
    (when verbose
      (println "PATCH" url "<-" body))
    (http/patch url
                {:headers (default-headers token)
                 :body body})))

(defn add-issue-comment
  "
  Add a comment to a GitHub issue. Returns the comment ID.
  "
  [repo issue-number comment-text & {:keys [verbose]}]
  (let [token (gh-token)
        url (str "https://api.github.com/repos/" repo "/issues/"
                 issue-number "/comments")
        body (json/generate-string {:body comment-text})]
    (when verbose
      (println "POST" url "<-" body))
    (-> (http/post url
                   {:headers (default-headers token)
                    :body body
                    :as :json})
        :body
        :id)))
