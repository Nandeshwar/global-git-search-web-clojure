(ns git-search.GitSearch
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f])
  (:use [clojure.string :only [last-index-of split blank? lower-case]]))

(def not-blank? (complement blank?))


(defn- append-str
  "if string does not exist at end, append one"
  [strP subStr]
  (let [lastIndexOfStr (last-index-of strP subStr)
        new-str  (if (not= lastIndexOfStr
                           (- (.length strP) 1))
                   (str strP subStr)
                    strP)]
    new-str))


(defn- convertJsonStr-to-jsonObj
  "
    Input: {
             'id': '1',
             'name: 'Nandeshwar'
           }
    Output:
           {
              :id 1
              :name Nandeshwar
           }
  "
  [jsonString]
  (json/read-str jsonString :key-fn keyword))


(defn- restCall
  "calls url and returns json"
  [url, user, password]
  (println "url=" url)
  (try (let [response (client/get url {:basic-auth [user password]})
        responseBody (convertJsonStr-to-jsonObj (response :body))]
    responseBody)
      (catch Exception e
        (cond
          (.contains (str e) "clj-http: status 401")
          (throw (Exception. "Authorization error: check user id or password"))
          (.contains (str e) "java.net.UnknownHostException")
          (throw (Exception. "Unknown host exception"))
          :else
          (throw (Exception. (str "rest call error=" e "url=" url)))))))


(defn- get-all-prj-names
  "get all the project names
  input:
  {
    :size 12,
    :limit 25,
    :isLastPage true,
    :values [
              {:key ESE,
               :id 81,
               :name ESE,
               :description Echostar Systems Engineering,
               :public false, :type NORMAL,
               :link {:url /projects/ESE, :rel self},
               :links {:self [{:href http://stash-stg01.sats.corp:7990/projects/ESE}]
              },

              {
                :key ESECONFIGS,
                :id 602, :name ESE-Configs,
                :public false, :type NORMAL,
                :link {:url /projects/ESECONFIGS, :rel self},
                :links {:self [{:href http://stash-stg01.sats.corp:7990/projects/ESECONFIGS}]}
              }
             ]
  }
  output: (ESE, ESECONFIGS)
  "
  [projInfo]
  (map :key (projInfo :values)))


(defn- git-project-info
  "Returns project info"
  [projectUrl, user, password]
  (restCall projectUrl user password))


(defn- get-project-repos
  "
  input1: project-url eg. http://stash-stg01.sats.corp:7990/rest/api/1.0/projects/
  input2: user
  input3: password
  input4: project-name

  returns map as object
  eg.
  {
    :project-name rms
    :repos (rmb rmnxg)
   }
   "
  [project-url user-name password project-name]
  (let [repo-url (str project-url project-name "/repos?limit=100")
        response (restCall repo-url user-name password)]
    {:project-name project-name
     :repos (map :slug (get response :values))}))


(defn- text-found?
  "check for match of every word of string in source string"
  [string-to-find source]
  (let [test (.contains (lower-case (get source :message)) (lower-case string-to-find))
        words-to-find (split (lower-case string-to-find) #"\s")
        source-msg (get source :message)
        words-count (count words-to-find)
        matched-words (filter #(.contains (lower-case source-msg) %) words-to-find)
        matched-count (count matched-words)]

    (= words-count matched-count)))


(defn- get-search-result
  "
  Return json object for the search result
  output example:
  {
    :url http://stash-stg01.sats.corp:7990/projects/LEARN/repos/clojure-git-search/commits/77ddefaa6e6ce336d8306777fb7668e4a3d536d5
    :author Sah
    :message introduced new module for git search
    :project-name learn
    :repo clojure-git-search
    :branch-name master
    :author-time 123456789
    :code-committed-time 2017-01-01 09:09:09
  }
  "
  [stash-url  project-name repo branch-name found-data-map]
  (let [author-time-long (get found-data-map :authorTimestamp)
        author-time-obj (c/from-long author-time-long)
        custom-formatter (f/formatter "yyyy-MM-dd hh:mm:ss")
        author-time-str (f/unparse custom-formatter author-time-obj)
        result {
                :url (str stash-url (get found-data-map :id))
                :author (get-in found-data-map [:author :name])
                :message (get found-data-map :message)
                :project-name project-name
                :repo repo
                :branch-name branch-name
                :author-time author-time-long
                :code-commited-time author-time-str
                }]
    result))


(defn- search-in-repo-branch
  "
  search text in branch and returns list of json map object for search result

  output example:
  check comment in method: search-in-git
  "
  [base-url project-url user-name password  search-string project-name repo page-no result-bag branch-name]
  (let [page page-no
        limit 10000
        repo-committed-code-url (str project-url project-name "/repos/" repo "/commits?until=" branch-name
                                     "&start=" page "&limit=" limit)
        response (try
                   (restCall repo-committed-code-url user-name password)
                   (catch Exception e (println "Error in method: Search in result="(str e)) nil))
        found-data-list (filter (partial text-found? search-string)
                                (get response :values))
        stash-url (str base-url "projects/" project-name "/repos/" repo "/commits/")
        search-result-list (map (partial get-search-result stash-url project-name repo branch-name) found-data-list)
        last-page? (get response :isLastPage)
        result-bag (into result-bag (vec search-result-list))]

    (if (or (true? last-page?) (nil? last-page?))
      result-bag
      (recur base-url project-url user-name password  search-string project-name repo
             (inc page-no) result-bag branch-name))))


(defn- search-in-repo
  "
  search in repo and different branches and accumulate the result and the return the same
  retuns json object list

  output example:
  check comment in method: search-in-git
  "
  [base-url project-url user-name password branch-names search-string project-name repo]

  (let [branch-url (str project-url project-name "/repos/" repo "/branches")
        branch-info (try
                      (restCall branch-url user-name password)
                      (catch Exception e (println (str "Error while trying to find out branch names=" e)) nil))

        new-branch-names (if (empty? branch-names)
                       (map :displayId (get branch-info :values))
                       branch-names)

        search-result-list-of-branch (if (empty? new-branch-names)
                                       nil
                                       (map (partial search-in-repo-branch
                                                     base-url
                                                     project-url
                                                     user-name
                                                     password
                                                     search-string
                                                     project-name
                                                     repo
                                                     0
                                                     [])
                                            new-branch-names))]
    (flatten search-result-list-of-branch)))


(defn- search-in-git
  "
  Accumulates search result of every repo of particular project.
  input: project-repos-map
  eg.
  {
    :project-name learn
    :repo [clojure-git-search abc xyz]
  }

  retuns json object list
  output example:
  (
    {
      :url http://stash-stg01.sats.corp:7990/projects/LEARN/repos/clojure-git-search/commits/77ddefaa6e6ce336d8306777fb7668e4a3d536d5
      :author: nks
      :message introduced new module for git search
      :project-name learn
      :repo clojure-git-search
      :branch-name master
      :author-time 123456789
      :code-committed-time 2017-01-01 09:09:09
    }
    {
      :url http://stash-stg01.sats.corp:7990/projects/LEARN/repos/clojure-git-search/commits/77ddefaa6e6ce336d8306777fb7668e4a3d536d5
      :author: nks
      :message introduced new module for git search
      :project-name learn
      :repo clojure-git-search
      :branch-name master
      :author-time 123456789
      :code-committed-time 2017-01-01 09:09:09
    }
  )
  "
  [base-url project-url user-name password branch-names search-string project-repos-map]

  (let [project-name (get project-repos-map :project-name)
        repos (get project-repos-map :repos)
        search-result-of-repo-list (map (partial search-in-repo
                                                 base-url
                                                 project-url
                                                 user-name
                                                 password
                                                 branch-names
                                                 search-string
                                                 project-name)
                                         repos)]
    (flatten search-result-of-repo-list)))


(defn- get-row [result counter]
  (str
    ;"<tr> <td>" counter ". </td></tr>"
    "<tr><td colspan='2'>" counter ". <a href=" (get result :url) "> click me for commit info </a> </td> </tr>"
    "<tr>
      <td>Author:</td>
      <td>"(get result :author)"</td>
      </tr>"
    "<tr>
      <td> Message: </td>
      <td>" (get result :message) "</td>
     </tr>
     <tr>
      <td>Project:</td>
      <td>" (get result :project-name) "</td>
     </tr>
     <tr>
      <td>Repository:</td>
      <td>" (get result :repo) "</td>
     </tr>
     <tr>
       <td>Branch: </td>
       <td>" (get result :branch-name) "</td
     </tr>
     <tr>
      <td>Date: </td>
      <td>" (get result :code-commited-time) "</td>
     </tr>
     <tr><td colspan='2'> <hr> </td></tr>"))


(defn- display-success-result-as-html
  [result all-repos all-repos-count]
  (str "<html><body>"
        "<table>"
       (let [result-rows (map get-row
                              result
                              (range 1 1000))
             space " "]
         (apply str (interpose \space result-rows)))
        "</table>
         <hr> Searched in the following " all-repos-count " repositories<br>"
       (vec all-repos)
       "</body></html>"))


(defn- display-failure-result-as-html
  [result all-repos all-repos-count]
  (str "<html><body>"
       result
       "<hr> Searched in the following " all-repos-count " repositories<br>"
       (vec all-repos)
       "</body></html>"))


(defn process
  "Accept git credentials and return search result"
  [baseUrlP branchNames userName userPassword txtProject txtRepo searchTxt]

  (let [lastIndexOfSlash (last-index-of baseUrlP "/")
        baseUrl (append-str baseUrlP "/")
        baseGitRestUrl (str baseUrl "rest/api/1.0/")
        projectUrl (str baseGitRestUrl "projects/")
        projInfo (git-project-info projectUrl userName userPassword)
        projectNames (get-all-prj-names projInfo)
        branch-names (if (not-blank? branchNames)
                       (split branchNames #"\s"))

        project-repos-map-list (cond
                                 (and (not-blank? txtProject)
                                      (not-blank? txtRepo))
                                 (let [repos (split txtRepo #"\s")
                                       project-repos-map-list [{:project-name txtProject :repos repos}]]
                                   project-repos-map-list)

                                 (and (not-blank? txtProject) (blank? txtRepo))
                                 (let [project-repos-map-list (get-project-repos projectUrl
                                                                                 userName
                                                                                 userPassword
                                                                                 txtProject)]
                                   [project-repos-map-list])

                                 :else
                                 (let [project-repos-map-list (map (partial get-project-repos
                                                                            projectUrl userName userPassword)
                                                                   projectNames)]
                                   project-repos-map-list))

        all-repos (flatten (map :repos project-repos-map-list))
        all-repos-count (count all-repos)

        search-result (flatten (map (partial search-in-git
                                                  baseUrl projectUrl userName userPassword branch-names searchTxt)
                                          project-repos-map-list))
        sorted-search-result-by-author-time (sort-by :author-time #(> %1 %2) search-result)]

    (if (or (nil? search-result) (empty? search-result))
      (display-failure-result-as-html "Not Found" all-repos all-repos-count)
      (display-success-result-as-html sorted-search-result-by-author-time all-repos all-repos-count))))

