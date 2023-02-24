(ns superlatives.models.circles
  (:require [datomic.client.api :as d]
            [superlatives.data.db :as db]
            [superlatives.models.questions :refer [create-question-obj]]
            [superlatives.utils.notifications :as notifs]
            [superlatives.utils.random :as rand]))


(def circle-schema [{:db/ident :circle/id
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity
                     :db/cardinality :db.cardinality/one
                     :db/doc "Circle's unique id"}

                    {:db/ident :circle/name
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/doc "Circle's name"}

                    {:db/ident :circle/members
                     :db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many
                     :db/doc "Circle's members"}

                    {:db/ident :circle/admin
                     :db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/one
                     :db/doc "Circle's admin"}

                    {:db/ident :circle/questions
                     :db/valueType :db.type/ref
                     :db/cardinality :db.cardinality/many
                     :db/doc "Circle's questions"}
                    
                    {:db/ident :circle/invite-code
                     :db/valueType :db.type/string
                     :db/unique :db.unique/identity
                     :db/cardinality :db.cardinality/one
                     :db/doc "Circle invite code"}])

(comment
  (d/transact db/conn {:tx-data circle-schema})
  (d/transact db/conn {:tx-data [{:db/ident :circle/invite-code
                                  :db/valueType :db.type/string
                                  :db/unique :db.unique/identity
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "Circle invite code"}]}))

(defn create-circle-obj [admin-id circle-name]
  {:circle/id (.toString (java.util.UUID/randomUUID))
   :circle/name circle-name
   :circle/members [[:user/id admin-id]]
   :circle/admin [:user/id admin-id]})

(defn create-circle [admin-id circle-name]
  (let [circle-obj (create-circle-obj admin-id circle-name)]
    (d/transact db/conn {:tx-data [circle-obj]})))

(comment
  (create-circle "0b9269b2-a584-4537-9d04-a62471565127" "Superlatives Team"))

(defn create-circle-with-questions [admin-id circle-name questions]
  (let [circle-obj (merge {:db/id "circle" 
                           :circle/invite-code (rand/rand-str 6)} (create-circle-obj admin-id circle-name))
        question-objs (->> questions
                           ; There might be some bug here because 'circle' isnt a valid circle id
                           (map #(merge {:db/id %} (create-question-obj % "circle")))
                           (map #(dissoc % :question/circle))
                           (map #(assoc % :question/circle "circle")) ; this doesn't do anything as far as i can tell, it should assoc (:circles/id circle-obj)
                           vec)
        circle-backrefs (->> questions (map #(identity [:db/add "circle" :circle/questions %])) vec)
        tx (-> [circle-obj]
               ((partial apply conj) question-objs)
               ((partial apply conj) circle-backrefs)
               vec)]
    (d/transact db/conn {:tx-data tx})))

(comment
  (create-circle-with-questions "a42b94c8-1504-440a-b574-5079202ed039"
                                "Chads"
                                ["Best person" "Worst person"]))

(defn get-circles [user-id]
  (let [data (d/q
              '[:find (pull ?circle pattern)
                :in $ ?userId pattern
                :where
                [?e :user/id ?userId]
                [?circle :circle/members ?e]]
              (d/db db/conn) user-id [:circle/id
                                      :circle/invite-code
                                      :circle/name
                                      {:circle/admin [:user/id]}
                                      {:circle/members [:user/id :user/first-name :user/last-name :user/profile-pic]}
                                      {:circle/questions [:question/id :question/text]}])
        serialized-data (map (fn [circle]
                               (update
                                (first circle)
                                :circle/members
                                (fn [members]
                                  (map (fn [member]
                                         [(:user/id member) (update
                                                             member
                                                             :user/profile-pic
                                                             #(.toString %))])
                                       members))))
                             data)]
    (into {} (map (fn [circle]
                    [(:circle/id circle) (update circle :circle/members (fn [x] (into {} x)))]) serialized-data))))

(comment

  (d/q
   '[:find (pull ?circle pattern)
     :in $ ?userId pattern
     :where
     [?e :user/id ?userId]
     [?circle :circle/members ?e]]
   (d/db db/conn) "0b9269b2-a584-4537-9d04-a62471565127" [:circle/id :circle/name {:circle/admin [:user/id]} {:circle/members [:user/id :user/first-name :user/profile-pic]} {:question/circle [:question/id :question/text]}])

  (get-circles "a42b94c8-1504-440a-b574-5079202ed039")
  #_(let [data (d/q '[:find (pull ?circle [:db/id])
                    :where
                    [?circle :circle/name _]]
                  (d/db db/conn))
        ids (->> data flatten (map :db/id))
        txs (->> ids (map #(identity [:db/add % :circle/invite-code (rand/rand-str 6)])))]
    (d/transact db/conn {:tx-data txs}))
  (let [circles (get-circles "a42b94c8-1504-440a-b574-5079202ed039")]
    (println (class (first (:members circles))))
    (:user/profile-pic (first (:members circles))))
  (conj [5 5] 5 5))

(defn get-members [circle-id]
  (let [data (d/q
              `[:find ?member
                :where
                [?circle :circle/id ~circle-id]
                [?circle :circle/members ?e]
                [?e :user/id ?member]]
              (d/db db/conn))]
    (-> data flatten vec)))

(comment
  (let [circle-id "7681ec77-f436-4224-8e81-4f473a2ad43f"
        data (d/q
              `[:find ?member
                :where
                [?circle :circle/id ~circle-id]
                [?circle :circle/members ?e]
                [?e :user/id ?member]]
              (d/db db/conn))]
    data)
  (get-members "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"))

(defn is-in [user-id circle-id]
  (let [data (d/q
              `[:find ?e
                :where
                [?circle :circle/id ~circle-id]
                [?e :user/id ~user-id]
                [?circle :circle/members ?e]]
              (d/db db/conn))]
    (-> data flatten vec count (= 1))))

(comment
  (is-in "3c18611a-7956-4911-8f74-8f20440681ab" "7681ec77-f436-4224-8e81-4f473a2ad43f"))

(defn add-user [circle to-user-phone]
  (d/transact db/conn {:tx-data [[:db/add [:circle/id circle] :circle/members [:user/phone to-user-phone]]]}))

(comment
  (add-user "7681ec77-f436-4224-8e81-4f473a2ad43f" "+19177569151")
  )

(defn remove-member [circle-id user-id]
  ; Delete rankings of this user for this circle
  (let [data (d/q '[:find (pull ?rankings pattern)
                    :in $ ?userId ?circleId pattern
                    :where
                    [?circle :circle/id ?circleId]
                    [?user :user/id ?userId]
                    [?rankings :rank/user ?user]
                    [?questions :question/circle ?circle]
                    [?rankings :rank/question ?questions]]
                  (d/db db/conn)
                  user-id
                  circle-id
                  [:db/id])
        entities (->> data flatten (map :db/id))
        txs (map #(identity [:db/retractEntity %]) entities)
        final-txs (-> txs
                      (conj [:db/retract [:circle/id circle-id] :circle/members [:user/id user-id]])
                      vec)]
    (d/transact db/conn {:tx-data final-txs})))

(comment
  (d/pull (d/db db/conn) [{:rank/user [:user/first-name]} {:rank/question [{:question/circle [:circle/name]}]}] 17592186046907)
  (let [data (d/q '[:find (pull ?rankings pattern)
                    :in $ ?userId ?circleId pattern
                    :where
                    [?circle :circle/id ?circleId]
                    [?user :user/id ?userId]
                    [?rankings :rank/user ?user]
                    [?questions :question/circle ?circle]
                    [?rankings :rank/question ?questions]]
                  (d/db db/conn)
                  "a42b94c8-1504-440a-b574-5079202ed039"
                  "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"
                  [:db/id])
        entities (->> data flatten (map :db/id))
        txs (map #(identity [:db/retractEntity %]) entities)
        final-txs (-> txs
                     #_(conj [[:db/retract]])
                      vec)]
    (d/transact db/conn {:tx-data final-txs}))
  (remove-member "9598d320-e3e9-4a80-9169-f7e8679ee9a5" "bd196518-0849-4cfe-a3c1-df50e40711bd")
  )

(defn is-admin [user-id circle-id]
  (let [[[admin-id]] (d/q 
              '[:find ?adminId
                :in $ ?circleId
                :where
                [?circle :circle/id ?circleId]
                [?circle :circle/admin ?admin]
                [?admin :user/id ?adminId]]
              (d/db db/conn) circle-id)]
    (= admin-id user-id)))
(comment
  (d/q
   '[:find ?adminId
     :in $ ?circleId
     :where
     [?circle :circle/id ?circleId]
     [?circle :circle/admin ?admin]
     [?admin :user/id ?adminId]]
   (d/db db/conn) "9598d320-e3e9-4a80-9169-f7e8679ee9a5"))

(defn remove-question [circle-id question-id]
  (let [[[real-circle-id]] (d/q 
              '[:find ?circleId
                :in $ ?questionId
                :where
                [?question :question/id ?questionId]
                [?question :question/circle ?circle]
                [?circle :circle/id ?circleId]]
              (d/db db/conn) question-id)]
    (when (= circle-id real-circle-id)
      (d/transact db/conn {:tx-data [[:db/retractEntity [:question/id question-id]]]}))))

(comment
  (d/q
   '[:find ?circleId
     :in $ ?questionId
     :where
     [?question :question/id ?questionId]
     [?question :question/circle ?circle]
     [?circle :circle/id ?circleId]]
   (d/db db/conn) "87ff1f1a-c7d1-4cc1-9390-b7f606340100")
  (remove-question "a02a70e0-ebc8-45fd-9f73-834b19d1a95f" "bd919240-2224-4d38-8d88-d6d97d164c77"))

(defn notify-circle! [circle-id title message]
  (let [circle-data (-> (d/q '[:find (pull ?circle circlePattern)
                               :in $ ?circleId circlePattern
                               :where
                               [?circle :circle/id ?circleId]]
                             (d/db db/conn) circle-id [:circle/name {:circle/members [:user/id :user/device-token]}])
                        ffirst)
        all-members (:circle/members circle-data)
        filtered-members (filter #(-> % :user/device-token nil? not) all-members)]
    ;Send notification to existing members of their new friend
    (->> filtered-members
         (map #(future
                 (notifs/send-notification 
                  (:user/device-token %) 
                  title 
                  message))))))

(defn check-invite-code [code]
  (let [data (d/pull (d/db db/conn) [:circle/id {:circle/admin [:user/id]}] [:circle/invite-code code])]
    data))

(defn add-by-invite-code [code user-id]
  (d/transact db/conn {:tx-data [[:db/add [:circle/id (:circle/id (check-invite-code code))] :circle/members [:user/id user-id]]]}))

(comment
  (:circle/id (check-invite-code "C6vN17"))
  (d/pull )
  (empty? {}))