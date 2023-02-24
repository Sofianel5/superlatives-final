(ns superlatives.core
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [org.httpkit.server :as http-kit]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            ;[ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.json :refer [wrap-json-body]]
            [nrepl.server :as nrepl-server]
            refactor-nrepl.middleware
            [cider.nrepl :as cider-nrepl]
            [superlatives.views.auth :as auth]
            [superlatives.views.circles :as circles]
            [superlatives.views.profile :as profile]
            [superlatives.views.showcases :as showcases]))

(defn home [_]
  "<a href='https://apps.apple.com/us/app/superlatives/id1598850742'>App Store</a><br>
   <a href='https://apps.apple.com/us/app/superlatives/id1598850742'>Google Play Store</a>")

(defn support-endpoint [_]
  "<p>For support, please contact romulus@outtacontrol.net</p>")

(defn download [_]
  {:status 307 :headers {"Location" "https://join.superlatives.app/download"} :body ""})

(defroutes all-routes
  (GET "/" [] home)
  (GET "/download" [] download)
  (GET "/support" [] support-endpoint)
  (POST "/api/auth/check-invite" [] auth/check-invite)
  (POST "/api/auth/redeem-invite-code" [] auth/redeem-invite-code)
  (POST "/api/auth/request-sign-up" [] auth/attempt-sign-up)
  (POST "/api/auth/verify-phone" [] auth/verify-phone)
  (POST "/api/auth/upload-pfp" [] auth/upload-profile-pic)
  (POST "/api/auth/complete-user" [] auth/complete-user)
  (POST "/api/auth/login" [] auth/login)

  (GET "/api/circles/get-circles" [] circles/render-get-circles)
  (POST "/api/circles/add-questions" [] circles/render-add-questions)
  (GET "/api/circles/get-rankings-in-circle" [] circles/get-rankings-in-circle)
  (POST "/api/circles/submit-vote" [] circles/submit-vote)
  (GET "/api/circles/question-packs" [] circles/get-question-packs)
  (POST "/api/circles/create-circle" [] circles/render-create-circle)
  (POST "/api/circles/invite-user" [] circles/render-invite-user)
  (GET "/api/circles/get-results" [] circles/render-get-results)
  (POST "/api/circles/remove-member" [] circles/render-remove-member)
  (POST "/api/circles/remove-question" [] circles/render-remove-question)
  (POST "/api/circles/report-superlative" [] circles/render-report)
  (POST "/api/circles/block" [] circles/render-leave)
  (POST "/api/circles/join" [] circles/render-join-circle)

  (GET "/api/profile/get-rankings" [] profile/get-user-rankings)
  (POST "/api/profile/reset-profile-pic" [] profile/reset-profile-pic)
  (POST "/api/profile/set-device-token" [] profile/set-device-token)

  (GET "/api/showcases/get" [] showcases/render-get-showcase)
  (POST "/api/showcases/add-superlative" [] showcases/render-add-superlative)
  (POST "/api/showcases/remove-superlative" [] showcases/render-remove-superlative)

  (GET "/showcases/:id" [id] showcases/render-showcase)

  (route/not-found "<p>Page not found.</p>")) ;; all other, return 404

(def app (-> all-routes
             ring.middleware.keyword-params/wrap-keyword-params
             ring.middleware.params/wrap-params
             ring.middleware.multipart-params/wrap-multipart-params
             (ring.middleware.json/wrap-json-body {:keywords? true})
             ;ring.middleware.anti-forgery/wrap-anti-forgery
             ring.middleware.session/wrap-session
             ring.middleware.reload/wrap-reload))

(defn start-repl! []
  (let [handler (apply nrepl-server/default-handler
                       (map resolve
                            (concat cider-nrepl/cider-middleware
                                    '[refactor-nrepl.middleware/wrap-refactor])))]
    (nrepl-server/start-server :bind "0.0.0.0"
                               :port 4004
                               :handler handler)))


(defn -main [& args]
  (start-repl!)
  (let [p (Integer/parseInt (or (System/getenv "PORT") "5000"))]
    (http-kit/run-server #'app {:ip "0.0.0.0", 
                                :port p
                                :max-body 10000000})
    (println "Server running on port" p)))