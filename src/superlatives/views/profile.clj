(ns superlatives.views.profile
  (:require [superlatives.models.rankings :as rankings]
            [superlatives.models.users :as users]
            [superlatives.views.utils :as utils]
            [compojure.core :refer :all]))

(defn get-user-rankings [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (utils/json-response {:status "success"
                            :data (rankings/get-rankings-for-user user-id)})
      (utils/json-response {:status "failed"
                            :reason "Unauthenticated"} 401))))

(defn reset-profile-pic [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (let [new-url (users/set-profile-picture user-id (-> req :params :image :tempfile))]
        (utils/json-response {:status "success"
                              :data {:url new-url}}))
      (utils/json-response {:status "failed"
                            :reason "Unauthenticated"} 401))))

(defn set-device-token [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (let [_ (users/set-device-token user-id (-> req :params :device-token))]
        (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"
                            :reason "Unauthenticated"} 401))))

(defroutes profile-routes
  (GET "/get-rankings" [] get-user-rankings)
  (POST "/reset-profile-pic" [] reset-profile-pic)
  (POST "/set-device-token" [] set-device-token))