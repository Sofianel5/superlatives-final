(ns superlatives.models.showcases
  (:require [datomic.client.api :as d]
            [superlatives.data.db :as db]))

(def showcase-schema [{:db/ident :showcase/title
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "Showcase's title"}

                      {:db/ident :showcase/user
                       :db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/one
                       :db/doc "User who owns showcase"}

                      {:db/ident :showcase/public
                       :db/valueType :db.type/boolean
                       :db/cardinality :db.cardinality/one
                       :db/doc "If showcase is public"}

                      {:db/ident :showcase/superlatives
                       :db/valueType :db.type/ref
                       :db/cardinality :db.cardinality/many
                       :db/doc "Showcase's superlatives"}])

(comment
  (d/transact db/conn {:tx-data showcase-schema}))

(defn create-showcase! [title user-id public superlatives-ids]
  (let [showcase-obj {:db/id "new-showcase"
                      :showcase/title title
                      :showcase/user [:user/id user-id]
                      :showcase/public public}
        showcase-superlatives (map #(identity [:db/add "new-showcase" :showcase/superlatives [:question/id %]]) superlatives-ids)
        tx (vec (conj showcase-superlatives showcase-obj))]
    (d/transact db/conn {:tx-data tx})))

(comment
  (create-showcase! "Sofiane's Superlatives" "a42b94c8-1504-440a-b574-5079202ed039" true ["a00311f8-2596-4dee-a217-b0e24eb938e6"])
  (let [users (d/q '[:find ?userId ?firstName
                     :in $
                     :where
                     [?u :user/id ?userId]
                     [?u :user/first-name ?firstName]] (d/db db/conn))]
    (map #(create-showcase! (str (second %) "'s Superlatives") (first %) false []) users)))

(defn add-superlative! [showcase-id superlative-id]
  (d/transact db/conn {:tx-data [[:db/add showcase-id :showcase/superlatives [:question/id superlative-id]]]}))

(defn remove-superlative! [showcase-id superlative-id]
  (d/transact db/conn {:tx-data [[:db/retract showcase-id :showcase/superlatives [:question/id superlative-id]]]}))

(comment
  (add-superlative! 17592186088681 "4b1893eb-8a3f-4880-8ec0-09fb89ce6ca9")
  (remove-superlative! 17592186088681 "4b1893eb-8a3f-4880-8ec0-09fb89ce6ca9"))

(defn get-showcase-data [showcase-id]
  (let [data (d/pull (d/db db/conn) [:db/id
                                     :showcase/title
                                     :showcase/public
                                     {:showcase/user
                                      [:db/id :user/first-name :user/last-name :user/profile-pic]}
                                     {:showcase/superlatives
                                      [:question/id
                                       :question/text
                                       {:question/circle [:circle/name :circle/members]}
                                       {:question/ranks [:rank/user :rank/value]}]}] showcase-id)
        filtered-data (assoc data :showcase/superlatives (filter (fn [superlative]
                                                                   (let [sorted-ranks (sort-by :rank/value > (:question/ranks superlative))]
                                                                     (= (-> sorted-ranks first :rank/user :db/id) (-> data :showcase/user :db/id)))) (:showcase/superlatives data)))
        trimmed-data (update filtered-data :showcase/superlatives (fn [x] (map #(update-in % [:question/circle :circle/members] count) x)))
        final-data (update trimmed-data :showcase/superlatives (fn [x] (map #(dissoc % :question/ranks) x)))]
    (update-in final-data [:showcase/user :user/profile-pic] #(.toString %))))

(comment
  (update-in {:a {:b [0 1]}} [:a :b] count))

(defn change-title! [showcase-id new-title]
  (d/transact db/conn {:tx-data [[:db/add showcase-id :showcase/title new-title]]}))

(comment
  (change-title! 17592186088681 "Sofiane's Superlatives"))

(defn change-public-status! [showcase-id public]
  (d/transact db/conn {:tx-data [[:db/add showcase-id :showcase/public public]]}))

(comment
  (let [showcases (d/q '[:find ?e
                         :in $
                         :where
                         [?e :showcase/public false]] (d/db db/conn))]
    (map #(change-public-status! % true) (flatten showcases)))
  (change-public-status! 17592186088681 true))

(defn delete-showcase! [showcase-id]
  (d/transact db/conn {:tx-data [[:db/retractEntity showcase-id]]}))

(comment
  (delete-showcase! 17592186089292)
  (d/q '[:find ?showcase
         :in $ ?userId
         :where
         [?user :user/id ?userId]
         [?showcase :showcase/user ?user]] (d/db db/conn) "a42b94c8-1504-440a-b574-5079202ed039"))

(defn get-user-showcase [user-id]
  (let [data (d/q '[:find ?showcase
                    :in $ ?userId
                    :where
                    [?user :user/id ?userId]
                    [?showcase :showcase/user ?user]] (d/db db/conn) user-id)]
    (ffirst data)))



