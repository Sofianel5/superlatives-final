(ns superlatives.models.rankings
  (:require [datomic.client.api :as d]
            [superlatives.data.db :as db]
            [superlatives.utils.misc :refer [dissoc-in]]
            [superlatives.utils.notifications :as notifs]))

(def rank-schema [{:db/ident :rank/user
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "Rank's user"}
                  
                  {:db/ident :rank/question
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "Rank's question"}
                  
                  {:db/ident :rank/value
                   :db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one
                   :db/noHistory true
                   :db/doc "Rank's value"}])

(def vote-schema [{:db/ident :vote/id
                   :db/valueType :db.type/string
                   :db/unique :db.unique/identity
                   :db/cardinality :db.cardinality/one
                   :db/doc "Rank's unique id"}

                  {:db/ident :vote/question
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "Rank's assoc'd question"}

                  {:db/ident :vote/user
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "User who originated rank"}

                  {:db/ident :vote/more
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "User who was voted more"}
                  
                  {:db/ident :vote/less
                   :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one
                   :db/doc "User who was voted less"}

                  {:db/ident :vote/comparison
                   :db/valueType :db.type/tuple
                   :db/tupleAttrs [:vote/more :vote/less]
                   :db/cardinality :db.cardinality/one
                   :db/doc "Comparison of [more less]"}])

(comment
  (d/transact db/conn {:tx-data rank-schema})
  (d/transact db/conn {:tx-data vote-schema})
  (d/transact db/conn {:tx-data [{:db/id :rank/question
                                  :db/cardinality :db.cardinality/one}]})
  (Math/pow 10 2))

(defn E [Ra Rb]
  (/ 1 (+ 1 (Math/pow 10 (/ (- Rb Ra) 400)))))

(def K 30)

(defn get-k [rating]
  (cond
    (> rating 2400) 10
    (> rating 1600) 20
    :else 40))

(defn new-scores [winner-old loser-old]
  [(Math/round 
    (+ winner-old 
       (* (get-k winner-old) (- 1 (E winner-old loser-old))))),
   (Math/round (+ loser-old (* (get-k loser-old) (- 0 (E loser-old winner-old)))))])

(def fast-new-scores (memoize new-scores))

(comment
  (time (new-scores 1385 1415))
  (time (fast-new-scores 1385 1415)))

(def STARTER-SCORE 1400)

; in the future, call this directly from the view and if its valid future record-vote and sync return the statistics of how others voted given the same question
(defn is-vote-valid [question ranker winner loser]
  (and (not= ranker winner)
       (not= ranker loser)
       ; Verify ranker is in the question's circle and identical vote has not been cast before
       #_(let [data (d/q 
                   '[:find (count ?vote) ?circle
                     :in $ questionId winnerId loserId
                     :where
                     [[:question/id questionId] :question/circle [:question/id questionId]]
                     []])])))

(defn get-rankings-for-question [question]
  (let [data (d/q
              `[:find ?userId ?value
                :where
                [?e :rank/question [:question/id ~question]]
                [?e :rank/value ?value]
                [?e :rank/user ?user]
                [?user :user/id ?userId]]
              (d/db db/conn))]
    (->> data
         (sort-by second >))))

(comment
  (get-rankings-for-question "a00311f8-2596-4dee-a217-b0e24eb938e6"))

(defn send-rank-notification [initial-rankings update superlative]
  (let [new-rankings (map #(cond 
                             (= (first %) (ffirst update)) [(first %) (-> update first second)]
                             (= (first %) (-> update second first)) [(first %) (-> update second second)]
                             :else %) initial-rankings)
        new-rankings (sort-by second > new-rankings)]
    (when (not= (ffirst new-rankings) (ffirst initial-rankings))
      (let [new-winner (ffirst new-rankings)
            old-winner (ffirst initial-rankings)
            new-winner-token (d/pull (d/db db/conn) [:user/device-token] [:user/id new-winner])
            old-winner-token (d/pull (d/db db/conn) [:user/device-token] [:user/id old-winner])]
        (when (-> new-winner-token empty? not)
          (notifs/send-notification 
           (:user/device-token new-winner-token) 
           "Congrats! 🎉" 
           "You've just earned a new Superlative!"
           {:route "Profile"
            :nestedRoute "ProfileScreen"
            :newSuperlative superlative}))
        (when (-> old-winner-token empty? not)
          (notifs/send-notification 
           (:user/device-token old-winner-token) 
           "Uh oh. 😱" 
           "You've just lost a Superlative. Go see what your friends are saying."
           {:route "Profile"
            :nestedRoute "ProfileScreen"
            :newSuperlative superlative}))))))

(comment
  (d/pull (d/db db/conn) [:user/first-name :user/last-name] [:user/id "40e7ab07-4bf5-497e-9c7b-bffe9367f805"])

  (send-rank-notification
   (get-rankings-for-question "f3266ca1-d04b-4880-870b-44c9adfb93e4")
   [["bd196518-0849-4cfe-a3c1-df50e40711bd" 100] ["a42b94c8-1504-440a-b574-5079202ed039" 2000]] "f3266ca1-d04b-4880-870b-44c9adfb93e4")
  )

; TODO: use with :in $ ..vars
(defn record-vote [question ranker winner loser]
  (when (is-vote-valid question ranker winner loser)
   (let [vote-obj {:vote/id (.toString (java.util.UUID/randomUUID))
                   :vote/question [:question/id question]
                   :vote/user [:user/id ranker]
                   :vote/more [:user/id winner]
                   :vote/less [:user/id loser]}
         [[prev-winner-rank r1]] (d/q
                                  `[:find ?winRank ?r1
                                    :where
                                    [?e1 :user/id ~winner]
                                    [?r1 :rank/user ?e1]
                                    [?r1 :rank/question [:question/id ~question]]
                                    [?r1 :rank/value ?winRank]]
                                  (d/db db/conn))
         [[prev-loser-rank r2]] (d/q
                                 `[:find ?loseRank ?r2
                                   :where
                                   [?e2 :user/id ~loser]
                                   [?r2 :rank/user ?e2]
                                   [?r2 :rank/question [:question/id ~question]]
                                   [?r2 :rank/value ?loseRank]]
                                 (d/db db/conn))
         [new-winner-rank new-loser-rank] (fast-new-scores (or prev-winner-rank STARTER-SCORE) (or prev-loser-rank STARTER-SCORE))]
     (future (send-rank-notification (get-rankings-for-question question) [[winner new-winner-rank] [loser new-loser-rank]] question))
     (d/transact db/conn {:tx-data (->> [vote-obj
                                         (when (not= nil r1) [:db/retract r1 :rank/value prev-winner-rank])
                                         (when (not= nil r2) [:db/retract r2 :rank/value prev-loser-rank])
                                         (if (not= nil r1)
                                           [:db/add r1 :rank/value new-winner-rank]
                                           {:db/id "r1"
                                            :rank/user [:user/id winner]
                                            :rank/question [:question/id question]
                                            :rank/value new-winner-rank})
                                         (if (not= nil r2)
                                           [:db/add r2 :rank/value new-loser-rank]
                                           {:db/id "r2"
                                            :rank/user [:user/id loser]
                                            :rank/question [:question/id question]
                                            :rank/value new-loser-rank})
                                         (when (= nil r1) [:db/add [:question/id question] :question/ranks "r1"])
                                         (when (= nil r2) [:db/add [:question/id question] :question/ranks "r2"])
                                         [:db/add "datomic.tx" :db/doc "Vote"]
                                         ]
                                        (filter #(not= nil %))
                                        vec)}))))

(comment
  


  (d/pull (d/db db/conn) [{:rank/question [:question/id]} {:rank/user [:user/auth-token :user/first-name]} :rank/value] 17592186046817)
  (filter #(not= nil %) [0 (when false 1)])
  (record-vote "8de7e293-3131-493b-9726-f0150204910c" ; question
               "960522a9-6769-46ff-aa63-bf7de113a7da" ; ranker
               "a42b94c8-1504-440a-b574-5079202ed039" ; winner
               "0b9269b2-a584-4537-9d04-a62471565127") ;loser
  (d/pull (d/db db/conn) [:user/first-name] [:user/id "0ace182d-d57f-4249-8bfe-494bde0677b6"])
  )


(comment
  (get-rankings-for-question "007e2183-04ca-404b-8cf8-bfc0e50e106f")
  (d/q
   '[:find (pull ?e [*])
     :where
     [?e :rank/question [:question/id "8de7e293-3131-493b-9726-f0150204910c"]]
     [?e :rank/user [:user/id "0b9269b2-a584-4537-9d04-a62471565127"]]]
   (d/db db/conn)))

(defn get-rankings-for-circle [circle]
  (let [data (d/q
              '[:find (pull ?circle pattern)
                :in $ ?circleId pattern
                :where
                [?circle :circle/id ?circleId]]
              (d/db db/conn) circle [{:circle/questions
                                      [:question/id
                                       :question/text
                                       {:question/ranks
                                        [{:rank/user [:user/id]} :rank/value]}]}
                                     {:circle/members [:user/id :user/first-name :user/last-name :user/profile-pic]}])
        res (ffirst data)
        members (set (->> res
                          :circle/members
                          (map :user/id)))
        filtered-res (update-in res [:circle/questions] (fn [questions]
                                                          (map (fn [question]
                                                                 (update-in question [:question/ranks] (fn [ranks]
                                                                                                         (->> ranks
                                                                                                              (filter #(contains? members (-> %
                                                                                                                                              :rank/user
                                                                                                                                              :user/id))))))) questions)))]
    (assoc filtered-res :circle/members (let [members (map (fn [m] (update m :user/profile-pic #(.toString %))) (:circle/members filtered-res))
                                              ids (map :user/id members)]
                                          (zipmap ids members)))
    #_(dissoc filtered-res :circle/members)))

(comment
  "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"
  (let [members (map (fn [m] (update m :user/profile-pic #(.toString %))) (:circle/members (get-rankings-for-circle "9598d320-e3e9-4a80-9169-f7e8679ee9a5")))
        ids (map :user/id members)]
    (zipmap ids members))
  (get-rankings-for-circle "9598d320-e3e9-4a80-9169-f7e8679ee9a5")
  (set (->> (get-rankings-for-circle "9598d320-e3e9-4a80-9169-f7e8679ee9a5")
           :circle/members
           (map :user/id)))
  (let [res (get-rankings-for-circle "9598d320-e3e9-4a80-9169-f7e8679ee9a5")
        members (set (->> res
                          :circle/members
                          (map :user/id)))]
    (println (count (:circle/members res)))
    (println (->> res
                  :circle/questions
                  (map (fn [question]
                         (count (-> question :question/ranks))))))
    (println (-> (update-in res [:circle/questions] (fn [questions]
                                                      (map (fn [question]
                                                             (update-in question [:question/ranks] (fn [ranks]
                                                                                                     (->> ranks
                                                                                                          (filter #(contains? members (-> %
                                                                                                                                          :rank/user
                                                                                                                                          :user/id))))))) questions)))
                 :circle/questions
                 (#(map (fn [question]
                          (count (-> question :question/ranks))) %)))))
)

(defn get-rankings-for-user [user]
  (let [data (d/q '[:find (pull ?ranks pattern)
                    :in $ ?userId pattern
                    :where
                    [?user :user/id ?userId]
                    [?ranks :rank/user ?user]
                    [?q :question/ranks ?ranks]
                    [?circles :circle/questions ?q]]
                  (d/db db/conn) user [:rank/value
                                       {:rank/question
                                        [:question/id
                                         :question/text
                                         {:question/circle ; This is not great because we dont need to be loading the same circle many times
                                          [:circle/name :circle/id]
                                          :question/ranks
                                          [:rank/value
                                           {:rank/user [:user/id
                                                        :user/first-name
                                                        :user/last-name
                                                        :user/profile-pic]}]}]}])]
    (->> data
        flatten
        (map (fn [rank]
               (-> rank
                   (assoc :index (let [l (sort #(> %1 %2) (->> rank :rank/question :question/ranks (map #(:rank/value %))))]
                                   (+ 1 (.indexOf l (:rank/value rank)))))
                   (update-in [:rank/question :question/ranks] (fn [ranks]
                                                                 (->> ranks
                                                                      (filter #(contains? % :rank/user))
                                                                      (map (fn [rank]
                                                                             (update-in rank [:rank/user :user/profile-pic] #(.toString %)))))))
                   #_(dissoc-in [:rank/question :question/ranks])))))))


(comment
  (time (get-rankings-for-user "a42b94c8-1504-440a-b574-5079202ed039"))

  (let [user "a42b94c8-1504-440a-b574-5079202ed039"
        data (d/q '[:find (pull ?ranks pattern)
                    :in $ ?userId pattern
                    :where
                    [?user :user/id ?userId]
                    [?ranks :rank/user ?user]
                    [?q :question/ranks ?ranks]
                    [?circles :circle/questions ?q]]
                  (d/db db/conn) user [:rank/value
                                       {:rank/question
                                        [:question/id
                                         :question/text
                                         {:question/circle ; This is not great because we dont need to be loading the same circle many times
                                          [:circle/name :circle/id]
                                          :question/ranks
                                          [:rank/value
                                           {:rank/user [:user/id
                                                        :user/first-name
                                                        :user/last-name
                                                        :user/profile-pic]}]}]}])]
    data))

(defn get-vote-counts [user-1 user-2 question-id]
  (let [votes-a (d/q '[:find ?a
                       :in $ ?userAId ?userBId ?questionId
                       :where
                       [?userA :user/id ?userAId]
                       [?userB :user/id ?userBId]
                       [?question :question/id ?questionId]
                       [?a :vote/question ?question]
                       [?a :vote/more ?userA]
                       [?a :vote/less ?userB]]
                     (d/db db/conn) user-1 user-2 question-id)
        votes-b (d/q '[:find ?b
                       :in $ ?userAId ?userBId ?questionId
                       :where
                       [?userA :user/id ?userAId]
                       [?userB :user/id ?userBId]
                       [?question :question/id ?questionId]
                       [?b :vote/question ?question]
                       [?b :vote/more ?userB]
                       [?b :vote/less ?userA]]
                     (d/db db/conn) user-1 user-2 question-id)]
    [(count votes-a) (count votes-b)]))

(comment
  (get-vote-counts "0b9269b2-a584-4537-9d04-a62471565127" 
                   "960522a9-6769-46ff-aa63-bf7de113a7da" 
                   "f6e93a9a-0835-4801-983c-d4bc37ac118f")
  
  (time (let [votes-a (d/q '[:find ?a
                       :in $ ?userAId ?userBId ?questionId
                       :where
                       [?userA :user/id ?userAId]
                       [?userB :user/id ?userBId]
                       [?question :question/id ?questionId]
                       [?a :vote/question ?question]
                       [?a :vote/more ?userA]
                       [?a :vote/less ?userB]]
                     (d/db db/conn) "445b0fcb-228f-4b16-a102-3156e62b9bf5" "67e614b6-c992-4b36-a983-3afc9029ef08" "cba63de4-7f54-4b68-9be1-dcce6fb79c24")
        votes-b (d/q '[:find ?b
                       :in $ ?userAId ?userBId ?questionId
                       :where
                       [?userA :user/id ?userAId]
                       [?userB :user/id ?userBId]
                       [?question :question/id ?questionId]
                       [?b :vote/question ?question]
                       [?b :vote/more ?userB]
                       [?b :vote/less ?userA]]
                     (d/db db/conn) "445b0fcb-228f-4b16-a102-3156e62b9bf5" "67e614b6-c992-4b36-a983-3afc9029ef08" "cba63de4-7f54-4b68-9be1-dcce6fb79c24")]
    [(count votes-a) (count votes-b)]))

(time  (d/q '[:find ?b
         :in $ ?userAId ?userBId ?questionId
         :where
         [?userA :user/id ?userAId]
         [?userB :user/id ?userBId]
         [?question :question/id ?questionId]
         [?b :vote/question ?question]
         [?b :vote/more ?userB]
         [?b :vote/less ?userA]
         ]
       (d/db db/conn) "0b9269b2-a584-4537-9d04-a62471565127" "960522a9-6769-46ff-aa63-bf7de113a7da" "665e94ff-5ba3-40e7-95fd-91e463c95e04"))
)