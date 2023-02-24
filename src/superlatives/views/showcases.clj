(ns superlatives.views.showcases
  (:require [superlatives.models.showcases :as showcases]
            [superlatives.models.users :as users]
            [superlatives.views.utils :as utils]
            [compojure.core :refer :all]
            [hiccup.core :as hiccup]
            [hiccup.page :refer [include-css]]
            [superlatives.data.constants :as constants]))

(defn render-showcase [req]
  (let [showcase-id (-> req :params :id Long/parseLong)
        showcase-data (showcases/get-showcase-data showcase-id)]
    (if (:showcase/public showcase-data)
      (hiccup/html
       [:html
        [:head
         (include-css "https://superlatives-files.s3.amazonaws.com/showcases.css")
         [:title (str (-> showcase-data :showcase/user :user/first-name) "'s Superlatives")]]
        [:body
         [:div {:class "title-holder"}
          [:h1 {:class "title"}
           (:showcase/title showcase-data)]]
         [:div {:class "profile-holder"}
          [:img {:src (-> showcase-data :showcase/user :user/profile-pic)
                 :class "profile-pic"}]]
         #_[:h2 {:class "name"}
          (str (-> showcase-data :showcase/user :user/first-name) " " (-> showcase-data :showcase/user :user/last-name))]
         (let [circles (group-by :question/circle (:showcase/superlatives showcase-data))]
           (for [circle circles]
             [:div {:class "circle-group"}
              [:h2 {:class "circle-title"}
               (-> circle first :circle/name)
               [:br]
               [:span {:class "member-count"}
                " (" (-> circle first :circle/members) " members)"]]
              [:div {:class "superlatives-holder"}
               (for [superlative (second circle)]
                 [:h3
                  {:class "superlative-text"}
                  (:question/text superlative)])]]))
         [:div {:class "inviteBtnHolder"}
          [:a {:class "inviteBtn"
               :href constants/download-url}
           "Get Superlatives"]
         [:div {"style" "height: 200px"}]]]])
      (hiccup/html
       [:h1 "Not found"]))))

(defn render-get-showcase [req]
  (let [user-id (utils/get-request-user req)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (utils/json-response {:status "success"
                            :data (showcases/get-showcase-data (showcases/get-user-showcase user-id))})
      (utils/json-response {:status "failed"} 400))))

(defn render-add-superlative [req]
  (let [user-id (utils/get-request-user req)
        superlative-id (-> req :params :superlative)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (let [showcase (showcases/get-user-showcase user-id)]
        (showcases/add-superlative! showcase superlative-id)
        (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"} 400))))

(defn render-remove-superlative [req]
  (let [user-id (utils/get-request-user req)
        superlative-id (-> req :params :superlative)
        auth-token (utils/get-auth-token req)
        auth-res (users/authenticate-user user-id auth-token)]
    (if auth-res
      (let [showcase (showcases/get-user-showcase user-id)]
        (showcases/remove-superlative! showcase superlative-id)
        (utils/json-response {:status "success"}))
      (utils/json-response {:status "failed"} 400))))

(defroutes showcase-routes
  (GET "/:id" [id] render-showcase)
  (GET "/get" [] render-get-showcase)
  (POST "/add-superlative" [] render-add-superlative)
  (POST "/remove-superlative" [] render-remove-superlative))