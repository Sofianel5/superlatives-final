(ns superlatives.utils.notifications
  (:require [superlatives.data.storage :as storage]
            [org.httpkit.client :as http]
            [cheshire.core :as json])
  (:import (com.google.firebase FirebaseOptions FirebaseOptions$Builder FirebaseApp)
           com.google.auth.oauth2.GoogleCredentials
           (com.google.firebase.messaging FirebaseMessaging Message MulticastMessage
                                          Notification AndroidConfig AndroidNotification
                                          ApnsConfig Aps)))

(def notifs-url "https://fcm.googleapis.com/v1/projects/superlatives-c2a9d/messages:send")

(defn get-access-token []
  (let [googleCredentials (-> (GoogleCredentials/fromStream (storage/get-priv-obj (System/getenv "SECRET_BUCKET") (System/getenv "FIREBASE_NOTIF_FILE")))
                              (.createScoped ["https://www.googleapis.com/auth/firebase.messaging"]))]
    (.refreshAccessToken googleCredentials)
    (.getTokenValue (.refreshAccessToken googleCredentials))))

(def access-token (atom (get-access-token)))

(defn send-notification [token title body & data]
  (let [body {:message {:notification {:title title
                                       :body body}
                        :token token}}
        req {:url notifs-url
             :method :post
             :accept :json
             :headers {"Authorization" (str "Bearer " @access-token)}
             :body (json/generate-string (if data (assoc-in body [:message :data] (first data)) body))}]
    (-> req
        http/request
        deref
        :status
        (= 401)
        (when
         (reset! access-token (get-access-token))
          (-> {:url notifs-url
               :method :post
               :accept :json
               :headers {"Authorization" (str "Bearer " @access-token)}
               :body (json/generate-string body)}
              http/request
              deref)))))

(comment
  (send-notification "dOta_594jkv3oxRM5ajvid:APA91bHhvTPmf5E2DV8_faPc9qd_gMg0Z-V4oAg-ymjpj0KugRUFnIh221MHo4LTtO2yCYWmhcrakhp4RPlLCicJQRLu88BNDfWX9Tt4Ws_IZwGQ8D82edBGn0YOC_jHE2FTJLB-2xdz"
                    "Hi sofiane"
                    "hi sofiane" 
                     {:route "Profile"
                      :nestedRoute "ProfileScreen"
                      :newSuperlative "9b3fbf99-4a1f-4694-839b-2d8a436a9351"})
  )