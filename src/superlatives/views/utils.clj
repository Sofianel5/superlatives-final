(ns superlatives.views.utils
  (:require  [ring.util.response :as response]
             [cheshire.core :as json]))

;; Helper utils -------------------------

(defn plaintext-response [body]
  (-> (response/response body)
      (response/content-type "text/plain")
      (response/charset "utf-8")))

(defn json-response [body & [status]]
  (-> body
      json/generate-string
      response/response
      (response/content-type "application/json")
      (response/charset "utf-8")
      (response/status (or status 200))))

(defn get-auth-token [req]
  (-> req :headers (get "authorization")))

(defn get-request-user [req]
  (-> req :headers (get "request-user")))
