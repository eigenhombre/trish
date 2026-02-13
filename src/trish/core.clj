(ns trish.core
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [trish.fetch :as fetch]
            [trish.time :as time])
  (:import [java.lang IllegalArgumentException])
  (:gen-class))

(def repos
  (if-let [repos-file (io/resource "repos.edn")]
    (edn/read-string (slurp repos-file))
    (throw (ex-info "repos.edn not found in resources directory" {}))))

(def title-segment-length 35)
(def default-user "eigenhombre")

(defn issue-num [issue] (:number issue))
(defn issue-title [issue] (:title issue))
(defn issue-closed-at [issue] (:closed_at issue))

(defn issue-older-than [issue time-fn age]
  (when-let [timestamp (time-fn issue)]
    (> age (time/age-in-days timestamp))))

(defn recently-closed-issue? [age issue]
  (issue-older-than issue issue-closed-at age))

(defn recently-updated-issue? [age issue]
  (issue-older-than issue :updated_at age))

(defn spaces [n]
  (apply str (repeat n " ")))

(defn wrap-title
  "
  Wrap a title to fit within title-segment-length characters,
  with subsequent lines indented by second-line-indent.
  "
  [second-line-indent title]
  (let [words (str/split title #"\s+")
        lines (reduce
               (fn [lines word]
                 (let [last-line (last lines)
                       new-line (if (empty? last-line)
                                  word
                                  (str last-line " " word))]
                   (if (<= (count new-line) title-segment-length)
                     (conj (vec (butlast lines)) new-line)
                     (conj lines word))))
               [""]
               words)]
    (if (= 1 (count lines))
      (str (first lines) "\n")
      (str (first lines) "\n"
           (str/join "" (map #(str (spaces second-line-indent) % "\n")
                             (rest lines)))))))

(defn issue-url->repo
  "Extract repository name from a GitHub issue URL.
  Example: https://api.github.com/repos/WIPACrepo/STM32Workspace/issues/988
           => WIPACrepo/STM32Workspace"
  [s]
  (->> (str/split s #"/")
       (drop 4)
       (take 2)
       (str/join "/")))

(defn issue-label-names [issue]
  (map :name (:labels issue)))

(defn format-issue
  "Format an issue for display."
  [{:keys [number title repo closed_at labels] :as issue}]
  (let [closed-at (when closed_at (subs closed_at 0 10))
        tags-str (str " (" (str/join " / " labels) ")")]
    (if closed-at
      (format "%36s %4d %10s -- %s"
              repo
              number
              closed-at
              (wrap-title 56 title))
      (format "%36s %4d %s"
              repo
              number
              (wrap-title 42 (str title tags-str))))))

(defn issue-summary
  "Extract a summary of key issue information."
  [issue]
  {:repo (issue-url->repo (:url issue))
   :number (:number issue)
   :title (:title issue)
   :closed_at (:closed_at issue)
   :created_at (:created_at issue)
   :updated_at (:updated_at issue)
   :closed-by (get-in issue [:closed_by :login])
   :labels (issue-label-names issue)
   :author (:author issue)})

(defn all-fetches
  "Fetch issues from all repositories in parallel."
  [repos & {:keys [state verbose assignee]}]
  (pmap #(fetch/fetch-issues %
                             :state state
                             :verbose verbose
                             :assignee assignee)
        repos))

(defn all-issues
  "Flatten all fetched issues into a single collection."
  [fetches]
  (mapcat identity fetches))

(defn closed-by-me? [issue]
  (= "eigenhombre" (:closed-by issue)))

(defn is-pr? [issue]
  (some? (:pull_request issue)))

(defn open-if-needed
  "Open an issue in the browser if openp is true."
  [openp {:keys [repo number] :as issue}]
  (when openp
    (shell/sh "open"
              (str "https://github.com/" repo "/issues/" number)))
  issue)

(defn issue-has-label?
  "Check if an issue has a specific label."
  [label issue]
  (some #(= label %) (:labels issue)))

(defn on-deck-issue? [{:keys [closed_at] :as issue}]
  (and (not closed_at)
       (issue-has-label? "on-deck" issue)))

(defn in-progress-issue? [issue]
  (issue-has-label? "in-progress" issue))

(defn bug-issue? [issue]
  (issue-has-label? "bug" issue))

(defn all-no-pr-issues [& {:keys [verbose]}]
  (->> (all-fetches repos :verbose verbose)
       all-issues
       (remove is-pr?)))

(defn all-open-no-pr-issues [& {:keys [verbose]}]
  (->> (all-fetches repos :state "open" :verbose verbose)
       all-issues
       (remove is-pr?)))

(defn my-issues [& {:keys [verbose]}]
  (->> (all-fetches repos
                    :verbose verbose
                    :state "open"
                    :assignee "eigenhombre")
       all-issues
       (remove is-pr?)))

(defn- maybe-parse-long [x]
  (try
    (parse-long x)
    (catch IllegalArgumentException _)))

(defn repo-issue-num
  "
  Parse an issue selector like 'wipacrepo/fh_icm_api/99' into repo and
  issue number.
  "
  [issue-selector]
  (let [[org repo num-str] (str/split issue-selector #"/")
        num (maybe-parse-long num-str)]
    (when num
      [(str org "/" repo) (parse-long num-str)])))

(defn issue-matches
  "
  Check if an issue matches a selector (either a plain number or
  org/repo/number format).
  "
  [{:keys [repo number]} issue-selector]
  (if-let [num (maybe-parse-long issue-selector)]
    (= number num)
    (let [[selector-repo selector-num] (repo-issue-num issue-selector)]
      (when (and selector-repo selector-num)
        (and (= (str/lower-case repo)
                (str/lower-case selector-repo))
             (= number selector-num))))))

(defn open-issue-matching-issue-num
  "Find an open issue matching the given issue number or selector."
  [issue-selector & {:keys [verbose]}]
  (->> (all-no-pr-issues :verbose verbose)
       (map issue-summary)
       (filter #(issue-matches % issue-selector))
       first))

(defn open-issues
  "Find issues by number (for any of the repos in repos),
  and open them in the browser."
  [issue-nums & {:keys [verbose]}]
  (doseq [issue-selector issue-nums]
    (let [{:keys [repo number]}
          (open-issue-matching-issue-num issue-selector
                                         :verbose verbose)]
      (if number
        (shell/sh "open"
                  (str "https://github.com/" repo
                       "/issues/" number))
        (println "No issue found.")))))

(defn issue-link
  "
  Format an issue as a Slack clickable link.
  "
  [repo number]
  (let [url (str "https://github.com/" repo "/issues/" number)
        text (str repo "#" number)]
    (str "<" url "|" text ">")))

(defn send-slack-notification
  "
  Send a notification to Slack if TRISH_SLACK_WEBHOOK is defined.
  Returns true if successful, false otherwise.
  "
  [message]
  (when-let [webhook (System/getenv "TRISH_SLACK_WEBHOOK")]
    (try
      (let [json-payload (json/generate-string {:text message})
            result (shell/sh "curl" "-X" "POST"
                            "-H" "Content-type: application/json"
                            "--data" json-payload
                            webhook)]
        (if (zero? (:exit result))
          true
          (do
            (println "Slack notification failed:" (:err result))
            false)))
      (catch Exception e
        (println "Failed to send Slack notification:" (.getMessage e))
        false))))

(defn workon-issue
  "
  Start working on an issue: reopen if closed, add in-progress label,
  remove on-deck and blocked labels, assign to self.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title closed_at]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (when closed_at
      (fetch/set-issue-state repo number "open" :verbose verbose))
    (fetch/add-issue-labels repo number ["in-progress"] :verbose verbose)
    (fetch/remove-issue-label repo number "on-deck" :verbose verbose)
    (fetch/remove-issue-label repo number "blocked" :verbose verbose)
    (fetch/set-issue-assignee repo number default-user :verbose verbose)
    (send-slack-notification
     (str "Started work on " (issue-link repo number) ": " title))))

(defn make-sibling-issue
  "Open a browser window to create a new issue in the same repo
  as the given issue."
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo]} (open-issue-matching-issue-num issue-selector
                                                           :verbose verbose)]
    (shell/sh "open"
              (str "https://github.com/" repo "/issues/new"))))

(defn take-issue
  "Assign an issue to yourself."
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number]} (open-issue-matching-issue-num
                                    issue-selector
                                    :verbose verbose)]
    (fetch/set-issue-assignee repo number default-user :verbose verbose)
    (send-slack-notification
     (str "Self-assigned " (issue-link repo number)))))

(defn drop-issue
  "
  Remove yourself from an issue and remove workflow labels.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number]} (open-issue-matching-issue-num
                                    issue-selector
                                    :verbose verbose)]
    (fetch/remove-issue-assignee repo number default-user :verbose verbose)
    (fetch/remove-issue-label repo number "in-progress" :verbose verbose)
    (fetch/remove-issue-label repo number "on-deck" :verbose verbose)
    (send-slack-notification
     (str "Self-unassigned " (issue-link repo number)))))

(defn make-on-deck-issue
  "
  Add an issue to the on-deck queue.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (fetch/add-issue-labels repo number ["on-deck"] :verbose verbose)
    (fetch/remove-issue-label repo number "in-progress" :verbose verbose)
    (send-slack-notification
     (str "Moved " (issue-link repo number) " to on-deck: " title))))

(defn tag-issue-as-blocked
  "
  Tag an issue as blocked.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number]} (open-issue-matching-issue-num
                                    issue-selector
                                    :verbose verbose)]
    (fetch/add-issue-labels repo number ["blocked"] :verbose verbose)
    (send-slack-notification
     (str "Tagged " (issue-link repo number) " as blocked."))))

(defn untag-issue-as-blocked
  "
  Remove blocked tag from an issue.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (fetch/remove-issue-label repo number "blocked" :verbose verbose)
    (send-slack-notification
     (str "Removed 'blocked' tag from " (issue-link repo number)))))

(defn tag-issue
  "
  Add an arbitrary tag to an issue.
  "
  [tag issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (fetch/add-issue-labels repo number [tag] :verbose verbose)
    (send-slack-notification
     (str "Tagged " (issue-link repo number) " with '" tag "': " title))))

(defn untag-issue
  "
  Remove an arbitrary tag from an issue.
  "
  [tag issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (fetch/remove-issue-label repo number tag :verbose verbose)
    (send-slack-notification
     (str "Removed tag '" tag "' from " (issue-link repo number) ": " title))))

(defn close-issue
  "
  Close an issue and remove workflow labels.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (fetch/remove-issue-label repo number "in-progress" :verbose verbose)
    (fetch/remove-issue-label repo number "on-deck" :verbose verbose)
    (fetch/remove-issue-label repo number "blocked" :verbose verbose)
    (fetch/set-issue-state repo number "closed" :verbose verbose)
    (send-slack-notification
     (str "Closed " (issue-link repo number) ": " title))))

(defn read-multiline-input
  "
  Read multiple lines of input from stdin until EOF (control-D).
  "
  []
  (println "Type your comment below...")
  (str/join "\n" (line-seq (java.io.BufferedReader. *in*))))

(defn comment-on-issue
  "
  Add a comment to an issue.
  "
  [issue-selector & {:keys [verbose]}]
  (when-let [{:keys [repo number title]}
             (open-issue-matching-issue-num issue-selector
                                            :verbose verbose)]
    (let [comment-text (read-multiline-input)
          comment-id (fetch/add-issue-comment repo
                                              number
                                              comment-text
                                              :verbose verbose)
          comment-url (str "https://github.com/" repo "/issues/" number
                           "#issuecomment-" comment-id)]
      (send-slack-notification
       (str "Commented on " (issue-link repo number) ": " title "\n"
            comment-text "\n"
            comment-url)))))

(def cli-options
  [["-v" "--verbose" "Verbose output"]
   ["-V" "--version" "Display version and exit"]
   ["-h" "--help" "Display help and exit"]
   ["-p" "--in-progress" "Show issues in progress"]
   ["-o" "--on-deck" "Show 'on deck' issues"]
   ["-r" "--recent" "Show recent issues"]
   [nil "--my-issues" "Show issues assigned to me"]
   ["-b" "--bugs" "Show bugs (issues tagged with 'bug')"]
   [nil "--raw" "Show raw results from GitHub"]
   ["-w" "--web" "Open matching results in browser"]
   [nil "--workon ISSUE" "Make issue 'in-progress'"
    :id :workon]
   [nil "--sibling ISSUE" "Make new issue sibling to supplied issue"
    :id :sibling]
   [nil "--take ISSUE" "Assign issue to self"
    :id :take]
   [nil "--drop ISSUE" "De-assign issue from self"
    :id :drop]
   [nil "--mkondeck ISSUE" "Make issue an 'on-deck' issue"
    :id :mkondeck]
   ["-c" "--close-issue ISSUE" "Close issue"
    :id :close-issue]
   ["-B" "--issue-blocked ISSUE" "Tag issue 'blocked'"
    :id :blocked]
   ["-u" "--issue-unblocked ISSUE" "Tag issue 'unblocked'"
    :id :unblocked]
   ["-t" "--tag TAG" "Add TAG to ISSUE (issue number as argument)"
    :id :tag]
   ["-x" "--untag TAG" "Remove TAG from ISSUE (issue number as argument)"
    :id :untag]
   ["-C" "--comment ISSUE" "Add comment to ISSUE"
    :id :comment]])

(defn print-issues! [coll]
  (run! print coll)
  (flush))

(defn present-issues
  "
  Common presentation logic for issues: apply bugs filter, sort, open in
  browser if requested, format and print.
  "
  [issues options & {:keys [sort-key]}]
  (let [filtered (cond->> issues
                   (:bugs options) (filter bug-issue?))
        sorted (if sort-key
                 (sort-by sort-key filtered)
                 filtered)]
    (->> sorted
         (map #(open-if-needed (:web options) %))
         (map format-issue)
         print-issues!)))

(defn run [arguments options]
  (let [verbose (:verbose options)]
    (when verbose
      (println "Arguments:" arguments "Options:" options))
    (cond
      ;; Handle tag option (uses positional argument):
      (:tag options)
      (when (seq arguments)
        (tag-issue (:tag options) (first arguments) :verbose verbose))

      ;; Handle untag option (uses positional argument):
      (:untag options)
      (when (seq arguments)
        (untag-issue (:untag options) (first arguments) :verbose verbose))

      ;; Handle positional arguments (issue numbers to open):
      (seq arguments)
      (open-issues arguments :verbose verbose)

      ;; Handle workflow management options:
      (:workon options)
      (workon-issue (:workon options) :verbose verbose)

      (:sibling options)
      (make-sibling-issue (:sibling options) :verbose verbose)

      (:take options)
      (take-issue (:take options) :verbose verbose)

      (:drop options)
      (drop-issue (:drop options) :verbose verbose)

      (:mkondeck options)
      (make-on-deck-issue (:mkondeck options) :verbose verbose)

      (:close-issue options)
      (close-issue (:close-issue options) :verbose verbose)

      (:blocked options)
      (tag-issue-as-blocked (:blocked options) :verbose verbose)

      (:unblocked options)
      (untag-issue-as-blocked (:unblocked options) :verbose verbose)

      (:comment options)
      (comment-on-issue (:comment options) :verbose verbose)

      ;; Handle display options:
      (:my-issues options)
      (let [issues (->> (my-issues :verbose verbose)
                        (map issue-summary))]
        (present-issues issues options :sort-key :updated_at))

      (:in-progress options)
      (let [issues (->> (all-fetches repos :state "open" :verbose verbose)
                        all-issues
                        (remove is-pr?)
                        (map issue-summary)
                        (filter in-progress-issue?))]
        (present-issues issues options))

      (:recent options)
      (let [issues (->> (all-fetches repos :state "open" :verbose verbose)
                        all-issues
                        (remove is-pr?)
                        (map issue-summary)
                        (filter #(recently-updated-issue? 30 %)))]
        (present-issues issues options :sort-key :updated_at))

      ;; Default behavior:
      :else
      (let [state (if (or (:on-deck options)
                          (:close-issue options)
                          (:bugs options))
                    "all"
                    "closed")
            non-pr-raw-issues (->> (all-fetches repos
                                                :state state
                                                :verbose verbose)
                                   all-issues
                                   (remove is-pr?))]
        (if (:raw options)
          (clojure.pprint/pprint non-pr-raw-issues)
          (let [summarized (map issue-summary non-pr-raw-issues)]
            (if (:on-deck options)
              (present-issues (filter on-deck-issue? summarized) options)
              (present-issues (->> summarized
                                   (filter closed-by-me?)
                                   (filter #(recently-closed-issue? 63 %)))
                              options
                              :sort-key :closed_at))))))))

(defn run-main [& args]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options)]
    (cond
      errors
      (do
        (doseq [error errors]
          (println error))
        1)

      (:help options)
      (do
        (println "trish - TRack ISsues Helpfully")
        (println)
        (println "Usage: trish [OPTIONS] [ISSUE-NUMBER...]")
        (println)
        (println summary)
        0)

      (:version options)
      (do
        (println "0.0.1")
        0)

      :else
      (do
        (run arguments options)
        0))))

(defn -main [& args]
  (let [ret (apply run-main args)]
    (shutdown-agents)
    (System/exit ret)))
