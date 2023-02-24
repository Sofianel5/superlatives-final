(ns superlatives.models.questions
  (:require [datomic.client.api :as d]
            [superlatives.data.db :as db]
            [superlatives.utils.notifications :as notifs]))

(def question-schema [{:db/ident :question/id
                       :db/valueType :db.type/string
                       :db/unique :db.unique/identity
                       :db/cardinality :db.cardinality/one
                       :db/doc "Question's unique id"}

                      {:db/ident :question/text
                       ;:db/fulltext true
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "Question's text"}

                      {:db/ident :question/ranks
                       :db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/many
                       :db/doc "User rankings"}

                      {:db/ident :question/circle
                       :db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/one
                       :db/doc "Circle question is asked in"}])

(comment
  (d/transact db/conn {:tx-data question-schema})
  (d/transact db/conn {:tx-data [{:db/ident :question/ranks
                                  :db/valueType :db.type/ref
                                  :db/cardinality :db.cardinality/many
                                  :db/doc "User rankings"}]})
  (first (read-string (slurp "src/superlatives/data/questionPacks.edn"))))

(defn create-question-obj [text circle-id]
  {:question/id (.toString (java.util.UUID/randomUUID))
   :question/text text
   :question/circle [:circle/id circle-id]})

(defn create-question [text circle-id]
  (let [question-id "new" 
        question-obj (merge {:db/id question-id} (create-question-obj text circle-id))
        _ (d/transact db/conn {:tx-data [question-obj
                                              [:db/add [:circle/id circle-id] :circle/questions question-id]]})]
    (-> question-obj
        (dissoc :db/id :question/circle)
        (assoc :question/ranks []))))



(defn create-questions [questions circle-id]
  (let [question-objs (->> questions
                           (map (comp #(merge {:db/id (:question/id %)} %)
                                      #(create-question-obj % circle-id))))
        tx (-> []
               ((partial apply conj) question-objs)
               ((partial apply conj) (->> question-objs
                                          (map #(identity [:db/add [:circle/id circle-id] :circle/questions (:question/id %)])))))]
    (future (d/transact db/conn {:tx-data tx}))
    (future (let [circle-data (-> (d/q '[:find (pull ?circle circlePattern)
                                         :in $ ?circleId circlePattern
                                         :where
                                         [?circle :circle/id ?circleId]]
                                     (d/db db/conn) circle-id [:circle/name {:circle/members [:user/id :user/device-token]}])
                                ffirst)
                all-members (:circle/members circle-data)
                filtered-members (filter #(-> % :user/device-token nil? not) all-members)]
            (->> filtered-members
                 (map #(future
                         (notifs/send-notification 
                          (:user/device-token %) 
                          "New superlative added‼️" 
                          (str "Vote on the new superlative just added to " (:circle/name circle-data) "!")
                          {:route "Vote"})))
                 doall
                 (keep deref))))
    (map (comp
          #(assoc % :question/ranks [])
          #(dissoc % :db/id :question/circle)) (filter map? tx))))


(comment
  
  (:circle/name (d/pull (d/db db/conn) [:circle/name] [:circle/id "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"]))
  (create-questions ["Most interesting background"] "d1dc0820-ddd6-4a23-bf4a-9c97c14363ee")
  (dissoc {:db/id "question-id"
            :question/id (.toString (java.util.UUID/randomUUID))
            :question/text "text"
            :question/circle [:circle/id ]} :db/id :question/circle)
  (->  {:db/id "question-id"
        :question/id (.toString (java.util.UUID/randomUUID))
        :question/text "text"
        :question/circle [:circle/id]}
       (dissoc :db/id :question/circle)
       (assoc :question/ranks []))
  (create-question "Who is more chad" "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"))

(defn get-questions [circle-id]
  (let [data (d/q
              `[:find ?questionId ?text 
                :keys :question-id :text
                :where
                [?circle :circle/id ~circle-id]
                [?question :question/circle ?circle]
                [?question :question/id ?questionId]
                [?question :question/text ?text]]
              (d/db db/conn))]
    data))

(comment
  (count (get-questions "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"))
  (let [q-id "5b35eed6-96fb-4902-9489-3be375641206"
        res (d/q
             '[:find ?q ?text ?circleName ?circleId
               :where
               [17592186046466 :question/id ?q]
               [17592186046466 :question/circle ?circle]
               [17592186046466 :question/text ?text]
               [?circle :circle/name ?circleName]
               [?circle :circle/id ?circleId]]
             (d/db db/conn))]
    res))

(defn get-question-rankings [question-id]
  (let [data (d/q
              `[:find ?firstName
                ;:keys :user :value
                :where
                [?question :question/id ~question-id]
                [?question :question/rankings ?rankV]
                [(first ?rankV) :user/first-name ?firstName]
                ;[?userE :user/last-name ?lastName]
                ]
              (d/db db/conn))]
    data))

(comment
  (get-question-rankings "52ee1498-9a09-4a2c-ad76-34733e12213c"))

(defn get-questions-for-user [user-id circle-id]
  (let [data (d/q '[:find (pull ?questions questionPattern) (pull ?votes votePattern) (pull ?members memberPattern)
                    :in $ ?userId ?circleId questionPattern votePattern memberPattern
                    :where
                    [?circle :circle/id ?circleId]
                    [?questions :question/circle ?circle]
                    [?circle :circle/members ?members]
                    [?user :user/id ?userId]
                    [?votes :vote/user ?user]]
                  (d/db db/conn) user-id circle-id '[:question/id] '[:vote/question] '[:user/id])]
    data))

(comment
  (time (let [circle-id "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"
              user-id "a42b94c8-1504-440a-b574-5079202ed039"
              data (d/q '[:find (pull ?questions questionPattern) (pull ?votes votePattern) (pull ?members memberPattern)
                          :in $ ?userId ?circleId questionPattern votePattern memberPattern
                          :where
                          [?circle :circle/id ?circleId]
                          [?questions :question/circle ?circle]
                          [?circle :circle/members ?members]
                          [?user :user/id ?userId]
                          [?votes :vote/user ?user]]
                        (d/db db/conn) user-id circle-id '[:question/id] '[:vote/question] '[:user/id])]
          data))
  (get-questions-for-user "a42b94c8-1504-440a-b574-5079202ed039 " "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"))