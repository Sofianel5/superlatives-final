(ns superlatives.data.db
  (:require [datomic.client.api :as d]
            [cognitect.aws.credentials :as credentials]))

(def local-config {:server-type :dev-local
                   :system "dev"})

(def on-prem-config {:server-type :peer-server
                     :access-key (System/getenv "DATOMIC_ACCESS_KEY")
                     :secret (System/getenv "DATOMIC_SECRET_KEY")
                     :endpoint "3.211.76.255:8998"
                     :validate-hostnames false})

(def client (d/client on-prem-config))

(defn init-db []
  (d/create-database client {:db-name "superlatives-v0-dev"}))

(comment
  (init-db))

(def conn (d/connect client {:db-name "superlatives-v0-dev"}))