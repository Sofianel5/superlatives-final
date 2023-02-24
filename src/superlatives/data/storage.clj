(ns superlatives.data.storage
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials])
  (:import org.apache.commons.io.FileUtils
           org.apache.commons.io.IOUtils))

(def s3 (aws/client {:api :s3
                     :region "us-east-1"
                     :credentials-provider (credentials/basic-credentials-provider
                                            {:access-key-id     (System/getenv "AWS_KEY")
                                             :secret-access-key (System/getenv "AWS_SECRET")})}))

(assert (aws/validate-requests s3 true))

(def bucket-name "superlatives-files")

(defn upload-bytes [filename file]
  (println file)
  (let [bytes (FileUtils/readFileToByteArray file)]
    (aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name 
                                             :Key filename
                                             :Body bytes}}))
  (str "https://" bucket-name ".s3.amazonaws.com/" filename))

(comment
  (let [bytes (.getBytes "HI there")]
    (aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name :Key "hi.txt"
                                             :Body bytes}}))
  (str "https://" bucket-name ".s3.amazonaws.com/" "hi.txt")
)

(defn get-priv-obj [bucket-name file]
  (-> (aws/invoke s3 {:op :GetObject :request {:Bucket bucket-name :Key file}})
      :Body))