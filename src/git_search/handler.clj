(ns git-search.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [git-search.GitSearch :as gitSearch]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]])
  (:use [ring.adapter.jetty             :only [run-jetty]]
        [compojure.core                 :only [defroutes GET POST]]
        [ring.middleware.params         :only [wrap-params]]))

(defroutes app-routes
  (GET "/" [] "Welcome. Let's start search in Git. Hit the link /search ")
  (POST "/search" [repository branchNames userName userPassword txtProject txtRepo searchTxt]
    {:status 200
     :headers {}
     :body (gitSearch/process repository branchNames userName userPassword txtProject txtRepo searchTxt)})

  (GET  "/search" []
    "<form method='post' action='/search'>
      <table>
        <tr>
          <td>Repository</td>
          <td><input type='text' value='http://stash-stg01.sats.corp:7990/' name='repository' size=35</td>
        </tr>

        <tr>
          <td>Branch </td>
          <td><input type='text' value='' name='branchNames' placeholder='optional' size=50</td>
        </tr>

        <tr>
          <td>User </td>
          <td><input type='text' value='Nandeshwa.Sah' name='userName' />
        </tr>

        <tr>
          <td>Password </td>
          <td><input type='password' value='KrishnaJi3###' name='userPassword' />
        </tr>

        <tr>
          <td> Project </td>
          <td> <input type='text' value='' placeholder='optional' name='txtProject' />
        </tr>

        <tr>
          <td> Repo </td>
          <td> <input type='text' value='' placeholder='optional' name='txtRepo' />
        </tr>

        <tr>
          <td> Search </td>
          <td> <input type='text' value='git search' name='searchTxt' size=80 />
        </tr>

        <tr>
          <td> <input type='submit'</td>
        </tr>
      </table>
    </form>"
  )

  (route/not-found "Not Found"))

(def app (wrap-params app-routes))

