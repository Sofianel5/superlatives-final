(ns superlatives.views.auth
  (:require [superlatives.views.utils :as utils]
            [superlatives.models.users :as users]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [try-let :refer :all])
  (:use [slingshot.slingshot :only [throw+ try+]]))

(defn check-invite [req]
  (let [phone (-> req :params :phone)]
    (if (users/validate-phone-invited phone)
      (utils/json-response {:invited true})
      (utils/json-response {:invited false}))))

(defn redeem-invite-code [req]
  (let [{:keys [phone invite-code]} (:params req)]
    (if (users/validate-invite-code invite-code phone)
      (utils/json-response {:status "success"})
      (utils/json-response {:status "failed"} 400))))

(defn attempt-sign-up [req]
  (let [{:keys [first-name last-name phone]} (:params req)]
    ;(println first-name last-name phone) ; sometimes + is interpeted as whitespsace so client needs to encode it as %2B
    (try+-let [[res-id invited] (users/handle-signup-request first-name last-name phone)]
      (utils/json-response {:status "success" 
                            :id res-id
                            :invited invited} 200)
      (catch Object _ (utils/json-response {:status "failed"
                            :reason (:object &throw-context)} 400)))))

(comment
  (attempt-sign-up {:params {:first-name "Sofiane" :last-name "Larbi" :phone "(646) 220-3750"}}))

(defn verify-phone [req]
  (let [{:keys [id phone verify]} (:params req)]
    (if-let [new-id (users/verify-code id phone verify)]
      (utils/json-response {:status "success" :id new-id} 200)
      (utils/json-response {:status "failed"} 400))))

(defn upload-profile-pic [req]
  (let [{:keys [id phone]} (:params req)]
    ;(println phone id)
    (if-let [[res-id filename] (users/set-pre-profile-picture phone id (-> req :params :image :tempfile))]
      (utils/json-response {:status "success" :id res-id :profile-pic filename} 200)
      (utils/json-response {:status "failed"} 400))))

(defn complete-user [req]
  (let [{:keys [id phone password]} (:params req)]
    (try+-let [res (users/matriculate-user id phone password)]
      (utils/json-response {:status "success" 
                            :data (users/get-user-map (:user/id res))} 200)
      (catch Object _ (utils/json-response {:status "failed"
                                            :reason (:object &throw-context)} 400)))))

(defn login [req]
  (let [{:keys [phone password]} (:params req)
        res (users/get-auth-token phone password)]
    (if res
      (utils/json-response {:status "success"
                            :data (users/get-user-map (first res))} 200)
      (utils/json-response {:status "failed"} 400))))

(defn password-reset-challenge [req]
  (let [phone (-> req :params)]))

(defroutes auth-routes
  (GET "/" [] "<p>Welcome to Superlatives auth!</p>")
  (POST "/check-invite" [] check-invite)
  (POST "/redeem-invite-code" [] redeem-invite-code)
  (POST "/request-sign-up" [] attempt-sign-up)
  (POST "/verify-phone" [] verify-phone)
  (POST "/upload-pfp" [] upload-profile-pic)
  (POST "/complete-user" [] complete-user)
  (POST "/login" [] login))