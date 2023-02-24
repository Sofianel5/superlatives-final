(ns superlatives.views.circles
  (:require [superlatives.views.utils :as utils]
            [superlatives.models.users :as users]
            [superlatives.models.circles :as circles]
            [superlatives.models.questions :as questions]
            [superlatives.models.rankings :as rankings]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [try-let :refer :all])
(:use [slingshot.slingshot :only [throw+ try+]]))

(defn render-get-circles [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (println user-id auth-token)
    (if auth-res
      (utils/json-response {:status "success" 
                            :data (circles/get-circles user-id)})
      (utils/json-response {:status "failed"
                            :reason "Unauthenticated"} 401))))

(defn render-add-questions [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-id (-> req :params :circle-id)
        new-questions (-> req :body)
        _ (println new-questions)]
    (if (and auth-res (circles/is-in user-id circle-id))
      (let [res (questions/create-questions new-questions circle-id)] 
        (utils/json-response {:status "success"
                              :data res} 201))
      (utils/json-response {:status "failed"} 401))))

(comment
  )

(defn get-rankings-in-circle [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-id (-> req :params :circle-id)]
    (if (and auth-res circle-id (circles/is-in user-id circle-id))
      (utils/json-response {:status "success"
                            :data (rankings/get-rankings-for-circle circle-id)})
      (utils/json-response {:status "failed"} 401))))

(defn submit-vote [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        question-id (-> req :params :question)
        winner-id (-> req :params :winner)
        loser-id (-> req :params :loser)]
    ; lol u forgot to check that the user is in this circle
    (if auth-res
      (if-let [_ (rankings/record-vote question-id user-id winner-id loser-id)]
        (utils/json-response {:status "success"} 200)
        (utils/json-response {:status "failed"} 400))
      (utils/json-response {:status "failed"} 401))))

(defn get-question-packs [req]
  ; Filter out questions already in the circle?
  (utils/json-response {:status "success"
                        :data (read-string (slurp "src/superlatives/data/questionPacks.edn"))} 200))

(defn render-create-circle [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-name (-> req :params :circle-name)
        question-pack (-> req :params :question-pack)
        questions (->> (read-string (slurp "src/superlatives/data/questionPacks.edn"))
                       (filter #(= (:name %) question-pack))
                       first
                       :questions)]
    (if auth-res
      (let [_ (circles/create-circle-with-questions user-id circle-name questions)]
        (utils/json-response {:status "success"
                              :data (circles/get-circles user-id)}))
      (utils/json-response {:status "failed"
                            :reason "Unauthenticated"} 401))))

(defn render-invite-user [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        invited-phone (-> req :params :phone)
        circle-id (-> req :params :circle-id)]
    (if (and auth-res circle-id (circles/is-in user-id circle-id))
      (let [_ (users/invite-user-to-circle user-id invited-phone circle-id)]
                (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"}))))

(defn render-get-results [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        question-id (-> req :params :question-id)
        user-1 (-> req :params :user-1)
        user-2 (-> req :params :user-2)] ; verify user is in the circle for this question
    (if auth-res
      (let [votes (rankings/get-vote-counts user-1 user-2 question-id)]
        (utils/json-response {:status "success"
                              :data votes}))
      (utils/json-response {:status "failed"}))))

(defn render-remove-member [req] 
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-id (-> req :params :circle-id)
        member (-> req :params :member-id)]
    (if (and auth-res circle-id member (circles/is-admin user-id circle-id))
      (let [_ (circles/remove-member circle-id member)
            data (rankings/get-rankings-for-circle circle-id)]
        (utils/json-response {:status "success"
                              :data data}))
      (utils/json-response {:status "failed"} 401))))

(defn render-remove-question [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-id (-> req :params :circle-id)
        question-id (-> req :params :question-id)]
    (if (and auth-res circle-id question-id (circles/is-admin user-id circle-id))
      (do (future (circles/remove-question circle-id question-id))
          (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"} 401))))

(defn render-report [_]
  (println "LMAOOO JE M'EN BAT LES CUILLES")
  ; TODO: ban user who reports shit
  (utils/json-response {:status "success"}))

(defn render-leave [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        circle-id (-> req :params :circle-id)]
    (if (and auth-res (circles/is-in user-id circle-id))
      (let [_ (circles/remove-member circle-id user-id)]
        (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"} 401))))

(defn render-join-circle [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)
        invite-code (-> req :params :invite-code)]
    (if auth-res
      (let [_ (circles/add-by-invite-code invite-code user-id)]
        (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"} 400))))

(defroutes circles-routes
  (GET "/get-circles" [] render-get-circles)
  (POST "/add-questions" [] render-add-questions)
  (GET "/get-rankings-in-circle" [] get-rankings-in-circle)
  (POST "/submit-vote" [] submit-vote)
  (GET "/question-packs" [] get-question-packs)
  (POST "/create-circle" [] render-create-circle)
  (POST "/invite-user" [] render-invite-user)
  (GET "/get-results" [] render-get-results)
  (POST "/remove-member" [] render-remove-member)
  (POST "/remove-question" [] render-remove-question)
  (POST "/report-superlative" [] render-report)
  (POST "/block" [] render-leave)
  (POST "/join" [] render-join-circle))