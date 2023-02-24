(ns superlatives.models.users
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [java-time]
            [phone-number.core :as phone]
            [slingshot.slingshot :refer :all]
            [datomic.client.api :as d]
            [superlatives.data.db :as db]
            [superlatives.models.circles :as circles]
            [superlatives.data.constants :refer [download-url]]
            [superlatives.data.storage :as store]
            [superlatives.utils.sms :as sms]
            [superlatives.utils.random :as random]
            [superlatives.utils.notifications :as notifs])
  (:import org.mindrot.jbcrypt.BCrypt))

(def invited-numbers-schema [{:db/ident :invited-numbers/number
                              :db/valueType :db.type/string
                              :db/unique :db.unique/identity
                              :db/cardinality :db.cardinality/one
                              :db/doc "Invited phone number"}
                             {:db/ident :invited-numbers/inviter
                              :db/valueType :db.type/ref
                              :db/cardinality :db.cardinality/many
                              :db/doc "Invited by"}
                             {:db/ident :invited-numbers/invited-circle
                              :db/valueType :db.type/ref
                              :db/cardinality :db.cardinality/many ; can be added to multiple groups
                              :db/doc "Invited to"}])

(def incomplete-user-schema [{:db/ident :incomplete-user/id
                              :db/valueType :db.type/string
                              :db/unique :db.unique/identity
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's unique id"}

                             {:db/ident :incomplete-user/first-name
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's first name"}

                             {:db/ident :incomplete-user/last-name
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's last name"}

                             {:db/ident :incomplete-user/phone
                              :db/valueType :db.type/string
                              :db/unique :db.unique/identity
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's phone number"}

                             {:db/ident :incomplete-user/profile-pic
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's profile pic"}

                             {:db/ident :incomplete-user/verify-code
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's verification code"}

                             {:db/ident :incomplete-user/verified
                              :db/valueType :db.type/boolean
                              :db/cardinality :db.cardinality/one
                              :db/doc "Incomplete user's verification status"}])

(def user-schema [{:db/ident :user/id
                   :db/valueType :db.type/string
                   :db/unique :db.unique/identity
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's unique id"}

                  {:db/ident :user/first-name
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's first name"}

                  {:db/ident :user/last-name
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's last name"}

                  {:db/ident :user/phone
                   :db/valueType :db.type/string
                   :db/unique :db.unique/identity
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's phone number"}

                  {:db/ident :user/password-hash
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's password hash"}

                  {:db/ident :user/auth-token
                   :db/valueType :db.type/string
                   :db/unique :db.unique/identity
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's unique api key"}
                  
                  {:db/ident :user/profile-pic
                   :db/valueType :db.type/uri
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's profile pic link"}
                  
                  {:db/ident :user/device-token
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one
                   :db/doc "User's device push notification token"}
                  
                  ])

(comment
  ;Transact the schema
  (d/transact db/conn {:tx-data user-schema})
  (d/transact db/conn {:tx-data [{:db/id :user/auth-key
                                  :db/ident :user/auth-token}]})
  (d/transact db/conn {:tx-data [{:db/id :user/password-hash
                                  :db/ident :user/unused-0}]})
  (d/transact db/conn {:tx-data [{:db/ident :user/device-token
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "User's device push notification token"}]})
  (d/transact db/conn {:tx-data [{:db/id :user/auth-token
                                  :db/ident :user/unused-1}
                                 {:db/id :user/id
                                  :db/ident :user/unused-2}]})
  (d/transact db/conn {:tx-data [{:db/ident :user/id
                                  :db/valueType :db.type/string
                                  :db/unique :db.unique/identity
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "User's unique id"}
                                 {:db/ident :user/auth-token
                                  :db/valueType :db.type/string
                                  :db/unique :db.unique/identity
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "User's unique api key"}]})
  (d/transact db/conn {:tx-data [{:db/ident :incomplete-user/verified
                                  :db/valueType :db.type/boolean
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "Incomplete user's verification status"}]})
  (d/transact db/conn {:tx-data [[:db/retract :invited-numbers/invited-circle :db/unique :db.unique/identity]
                                 [:db/add :db.part/db :db.alter/attribute :invited-numbers/invited-circle]]})
  (d/transact db/conn {:tx-data invited-numbers-schema})
  (d/transact db/conn {:tx-data incomplete-user-schema})
  (d/q
   '[:find ?attr ?type ?card
    :where
    [_ :db.install/attribute ?a]
    [?a :db/valueType ?t]
    [?a :db/cardinality ?c]
    [?a :db/ident ?attr]
    [?t :db/ident ?type]
    [?c :db/ident ?card]]
   (d/db db/conn)))
   

(defn hashpw [raw]
  (BCrypt/hashpw raw (BCrypt/gensalt 12)))

(defn checkpw [raw hashed]
  (boolean (BCrypt/checkpw raw hashed)))

(defn gen-auth-token []
  (.toString (java.util.UUID/randomUUID)))

(defn validate-phone-unique [phone]
  (let [data (d/q
              `[:find (count ?e)
                :where
                [?e :user/phone ~phone]]
              (d/db db/conn))]
    (-> data flatten count (= 0))))

(comment
  (validate-phone-unique "+1646203750")
  (BCrypt/checkpw "nil" "nil"))

(defn validate-phone-invited [phone]
  (let [data (d/q
              `[:find (count ?e)
                :where
                [?e :invited-numbers/number ~phone]]
              (d/db db/conn))]
    (-> data flatten count (> 0))))

(defn invite-number-to-app [inviter-id invited-phone invited-circle-id]
  (let [invite-obj {:db/id "new"
                    :invited-numbers/number invited-phone
                    :invited-numbers/inviter [[:user/id inviter-id]]
                    :invited-numbers/invited-circle [[:circle/id invited-circle-id]]}]
    (d/transact db/conn {:tx-data [invite-obj]})
    #_(let [[[first-name circle-name]] (d/q
                                        `[:find ?fname ?circleName
                                          :where
                                          [?e :user/id ~inviter-id]
                                          [?e :user/first-name ?fname]
                                          [?c :circle/id ~invited-circle-id]
                                          [?c :circle/name ?circleName]]
                                        (d/db db/conn))]
        (sms/send-sms invited-phone (str "You've passed the vibe check. Your friend " first-name " just added you to " circle-name " on Superlatives. Sign up and join them here: " download-url)))))

(defn validate-invite-code [code phone]
  (let [invited-circle (circles/check-invite-code code)]
    (when (-> invited-circle empty? not)
      (invite-number-to-app (-> invited-circle :circle/admin :user/id) phone (:circle/id invited-circle))
      true)))

(comment
  (validate-invite-code "C6vN17" "+19173703059")
  (d/q
   '[:find (pull ?e [:invited-numbers/number])
     :where
     [?e :invited-numbers/number]]
   (d/db db/conn))
  (time (phone/valid? "+14042203750"))"+19173703059"
  (time (validate-phone-invited "+16502839050")))

(defn validate-user [cleartext-password phone first-name last-name profile-pic verified & invite-code]
  (cond-> {:password [] :phone [] :first-name [] :last-name [] :profile-pic []}
    (empty? cleartext-password) (update :password conj "Password must exist")
    (empty? phone) (update :phone conj "Must provide phone number")
    (not verified) (update :phone conj "Must validate phone number")
    (empty? first-name) (update :first-name conj "Must provide first name")
    (empty? last-name) (update :last-name conj "Must provide last name")
    (empty? profile-pic) (update :profile-pic conj "Must provide profile pic")
    (not (validate-phone-unique phone)) (update :phone conj "Phone number already registered")
    ;(not (or (validate-phone-invited phone) (validate-invite-code (first invite-code) phone))) (update :phone conj "You haven't been invited to Superlatives yet.")
    (not (phone/valid? phone)) (update :phone conj "Invalid phone number")))

(defn is-user-valid [m]
  (every? empty? (vals m)))

(defn standardize-phone [s] (-> s
                                str
                                str/trim))

(defn standardize-name [s] (->> s
                                str
                                (#(str/split % #"\b"))
                                (map str/capitalize)
                                str/join))

(comment
  (standardize-phone "+16462203750 "))

(defn get-invited-circles [phone]
  (let [data (d/q
              `[:find ?circles
                :where
                [?e :invited-numbers/number ~phone]
                [?e :invited-numbers/invited-circle ?c]
                [?c :circle/id ?circles]]
              (d/db db/conn))]
    (->> data flatten set (apply list))))

(comment
  (get-invited-circles "+1916953091"))

(defn notify-circle-of-new-user! [circle-id new-user-phone]
  (let [circle-data (-> (d/q '[:find (pull ?circle circlePattern)
                               :in $ ?circleId circlePattern
                               :where
                               [?circle :circle/id ?circleId]]
                             (d/db db/conn) circle-id [:circle/name {:circle/members [:user/id :user/device-token]}])
                        ffirst)
        all-members (:circle/members circle-data)
        new-member (d/pull (d/db db/conn) [:user/id :user/first-name :user/last-name :user/device-token] [:user/phone new-user-phone])
        filtered-members (filter #(and (not= (:user/id %) (:user/id new-member)) (-> (:user/device-token %) nil? not)) all-members)]
    ;Send notification to added member
    (when (-> new-member :user/device-token nil? not)
      (future
        (notifs/send-notification (:user/device-token new-member) 
                                  "Say Hi!" 
                                  (str "You've been added to " (:circle/name circle-data))
                                  {:route "Circles"
                                   :nestedRoute "Circles"})))
    ;Send notification to existing members of their new friend
    (->> filtered-members
         (map #(future
                 (notifs/send-notification 
                  (:user/device-token %) 
                  "Say Hi!" 
                  (str "Your friend " (:user/first-name new-member) " has joined " (:circle/name circle-data))
                  {:route "Circles"
                   :nestedRoute "Circles"})))
         doall
         (keep deref))))

;; returns new user map
;; TODO: Validate username, password, phone
(defn create-user! [cleartext-password phone first-name last-name profile-pic verified]
  (let [phone (standardize-phone phone)
        first-name (standardize-name first-name)
        last-name (standardize-name last-name)
        validation-res (validate-user cleartext-password phone first-name last-name profile-pic verified)]
    (if (is-user-valid validation-res) (let [user-obj {:user/id (gen-auth-token)
                                                       :user/first-name first-name
                                                       :user/last-name last-name
                                                       :user/password-hash (hashpw cleartext-password)
                                                       :user/phone phone
                                                       :user/auth-token (gen-auth-token)
                                                       :user/profile-pic (new java.net.URI profile-pic)}]
                                         (d/transact db/conn {:tx-data [user-obj]})
                                         (future (->> phone
                                                      get-invited-circles
                                                      (map #(do
                                                              (circles/add-user % phone)
                                                              (notify-circle-of-new-user! % phone)))
                                                      doall))
                                         user-obj) ; Now add them to the circles that they invited to
        ; Should probably throw
        (throw+ validation-res))))

(comment
  
  (count (d/q '[:find #_?deviceToken ?userFirstName ?userLastName
         :in $
         :where
         [?u :user/first-name #_"Sofiane" ?userFirstName]
         [?u :user/last-name ?userLastName]
         #_[?u :user/device-token ?deviceToken]] (d/db db/conn)))
  (count (d/q '[:find ?invitedPhone
                :in $
                :where 
                [?invite :invited-numbers/number ?invitedPhone]] (d/db db/conn)))
  (:user/device-token (d/pull (d/db db/conn) [:user/device-token] [:user/id "a42b94c8-1504-440a-b574-5079202ed039"]))
  (d/pull (d/db db/conn) [:circle/name] [:circle/invite-code "XJpDti"])
  (count (d/q '[:find ?circleName (pull ?admin pattern) ?code (count ?members)
                :in $ pattern
                :where
                [?c :circle/name ?circleName]
                [?c :circle/admin ?admin]
                [?c :circle/members ?members]
                [?c :circle/invite-code ?code]
                #_[?q :question/circle ?c]] (d/db db/conn) [:user/first-name]))
  (d/pull (d/db db/conn) [:user/first-name :user/last-name :user/phone] [:user/id "a0ffa30d-1f57-474e-b21a-50011a1adebf"])
  (d/q '[:find ?inviterName
         :in $ ?invitedPhone
         :where
         [?invite :invited-numbers/number ?invitedPhone]
         [?invite :invited-numbers/inviter ?inviter]
         [?inviter :user/first-name ?inviterName]] (d/db db/conn) "+15165922603")
  (time (standardize-phone "+16462203750"))
  (time (validate-phone-invited "+16462203750"))

  (time (validate-phone-unique "+1646223750000"))
  (phone/valid? "+16464206969")
  (create-user! "Password" "+16462203750" "Sofiane" "Larbi" "https://sofianelarbi.com" true)
  (create-user! "password" "+19177569151" "Liam" "Kronman" "https://superlatives-files.s3.amazonaws.com/ooc-kk.jpg" true)
  (create-user! "password" "+16464206969" "Tim" "Apple" "https://superlatives-files.s3.amazonaws.com/tim-cook.png" true)
  (create-user! "password" "+19172850807" "Jason" "Seo" "https://jasonseo.com" true)
  (time (future (->> "+19172850807"
                     get-invited-circles
                     (map #(future (circles/add-user % "+19172850807")))
                     doall
                     (keep deref))))
  (d/q
   '[:find ?id ?pword ?auth-tkn
     :where
     [?e :user/phone "+16462203750"]
     [?e :user/id ?id]
     [?e :user/password-hash ?pword]
     [?e :user/auth-token ?auth-tkn]]
   (d/db db/conn))
  (d/q
   '[:find ?firstName ?lastName ?phone ?id ?authToken
     :where
     [?e :user/phone ?phone]
     [?e :user/first-name ?firstName]
     [?e :user/last-name ?lastName]
     [?e :user/id ?id]
     [?e user/auth-token ?authToken]]
   (d/db db/conn))

  (d/transact db/conn {:tx-data [[:db/add [:user/id "b1a564a8-7843-4a81-b577-4db2d54f4c08"] :user/phone "+150023750"]]})
  (let [user-obj {:user/id (gen-auth-token)
                  :user/first-name "Admin"
                  :user/last-name "User"
                  :user/password-hash (hashpw "superlatives-genesis")
                  :user/phone "+17064206906"
                  :user/auth-token (gen-auth-token)
                  :user/profile-pic (new java.net.URI "https://superlatives.app")}]
    (d/transact db/conn {:tx-data [user-obj]}))
  )

(defn set-password! [id new-password]
  (d/transact db/conn {:tx-data [[:db/add [:user/id id] :user/password-hash (hashpw new-password)]]}))

(comment
  (d/q '[:find ?userId
         :in $ 
         :where
         [?u :user/first-name "Eric"]
          [?u :user/id ?userId]] (d/db db/conn))
  (d/pull (d/db db/conn) [:user/last-name] [:user/id "de9e9ccb-e79b-4d99-9724-fc9e6f813c2e"])
  (set-password! "de9e9ccb-e79b-4d99-9724-fc9e6f813c2e" "pazzword123"))

(defn set-auth-token! [id token]
  (d/transact db/conn {:tx-data [[:db/add [:user/id id] :user/auth-token token]]}))

(comment
  (set-auth-token! "3c18611a-7956-4911-8f74-8f20440681ab" (gen-auth-token)))

(defn get-auth-token [phone password]
  (let [[[user-id correct-pass-hash auth-token]] (d/q
                     `[:find ?id ?passw ?auth-tkn
                       :where
                       [?e :user/phone ~phone]
                       [?e :user/id ?id]
                       [?e :user/password-hash ?passw]
                       [?e :user/auth-token ?auth-tkn]]
                     (d/db db/conn))]
    (if (checkpw password correct-pass-hash)
      [user-id auth-token]
      nil)))

(comment
  (get-auth-token "+16462203750" "new-password")
  (d/pull (d/db db/conn) [:user/first-name :user/last-name] [:user/id "86583555-04ed-40b1-a6af-d7839e147261"])
  )

(defn authenticate-user [user-id auth-token]
(if (and (not (nil? user-id)) (not (nil? auth-token)))
  (let [[[true-auth-token]] (d/q
                             `[:find ?auth-tkn
                               :where
                               [?e :user/id ~user-id]
                               [?e :user/auth-token ?auth-tkn]]
                             (d/db db/conn))]
    (if (= auth-token true-auth-token)
      true
      false))
  false))

(comment
  (d/q
   `[:find ?token ?fname ?lname ?phone ?pfp ?auth-tkn
     :where
     [?e :user/id "a42b94c8-1504-440a-b574-5079202ed039"]
     [?e :user/first-name ?fname]
     [?e :user/last-name ?lname]
     [?e :user/phone ?phone]
     [?e :user/profile-pic ?pfp]
     [?e :user/auth-token ?auth-tkn]
     [?e :user/device-token ?token]]
   (d/db db/conn))
  (authenticate-user "3c18611a-7956-4911-8f74-8f20440681ab" "7f33ff4a-2d65-4007-adb5-30a4eecf7fbe")
  )

(defn get-user-map [user-id]
  (let [[[first-name last-name phone profile-pic auth-token]] (d/q
                                                  `[:find ?fname ?lname ?phone ?pfp ?auth-tkn
                                                    :where
                                                    [?e :user/id ~user-id]
                                                    [?e :user/first-name ?fname]
                                                    [?e :user/last-name ?lname]
                                                    [?e :user/phone ?phone]
                                                    [?e :user/profile-pic ?pfp]
                                                    [?e :user/auth-token ?auth-tkn]]
                                                  (d/db db/conn))]
    {:id user-id
     :first-name first-name
     :last-name last-name
     :phone phone
     :profile-pic (.toString profile-pic)
     :auth-token auth-token
     :circles (circles/get-circles user-id)}))

(defn clean-user [user-obj]
  (dissoc user-obj :password-hash))

(comment
  (get-user-map "b46814ba-c77e-45c9-871c-bf56822e5f7f")
)

(comment
  (d/q
   `[:find ?number ?i
     :where
     [?i :invited-numbers/invited-circle [:circle/id "a02a70e0-ebc8-45fd-9f73-834b19d1a95f"]]
     [?i :invited-numbers/number ?number]]
   (d/db db/conn))
  (sms/send-sms "+16462203750" (str "You've passed the vibe check. Your friend " "Liam" " just added you to " "Randos" " on Superlatives. Sign up and join them here: " download-url))
  (validate-phone-invited "+16506508160")
  
  (phone/valid? "+19174201234")
  (invite-number-to-app "a42b94c8-1504-440a-b574-5079202ed039" "+19174201234" "a02a70e0-ebc8-45fd-9f73-834b19d1a95f")
  )

(defn invite-user-to-circle [inviter-id invited-phone invited-circle-id]
  (if (validate-phone-unique invited-phone)
    (invite-number-to-app inviter-id invited-phone invited-circle-id)
    ; Add user to circle
    ; Check if inviter is in circle first
    (do 
      (circles/add-user invited-circle-id invited-phone)
      (future (notify-circle-of-new-user! invited-circle-id invited-phone)))))

(comment
  (notify-circle-of-new-user! "9598d320-e3e9-4a80-9169-f7e8679ee9a5" "+16462203750")
  (sms/send-sms "+19172850807" 
                (str "You've passed the vibe check. Your friend " "Sofiane" " just added you to " "Sigma Males" " on Superlatives. Join them here: " download-url))
  
  (invite-user-to-circle "3c18611a-7956-4911-8f74-8f20440681ab" "+19177569151" "7681ec77-f436-4224-8e81-4f473a2ad43f")
  (invite-user-to-circle "3c18611a-7956-4911-8f74-8f20440681ab" "+19172850807" "7681ec77-f436-4224-8e81-4f473a2ad43f"))

(defn handle-signup-request [first-name last-name phone]
  (let [[first-name last-name] (keep standardize-name [first-name last-name])
        phone (standardize-phone phone)
        validation-res (dissoc (validate-user "" phone first-name last-name "" true) :password :profile-pic)]
    (if (is-user-valid validation-res)
      ;check if this works for updating automatically too
      (let [uid (gen-auth-token)
            code (random/rand-num-str 6)
            invited (validate-phone-invited phone)
            incomplete-user-obj {:incomplete-user/id uid
                                 :incomplete-user/first-name first-name
                                 :incomplete-user/last-name last-name
                                 :incomplete-user/phone phone
                                 :incomplete-user/verify-code code}]
        (future (d/transact db/conn {:tx-data [incomplete-user-obj]})
                (sms/send-sms phone (str "Your Superlatives verification code is " code)))
        [uid invited])
      (throw+ validation-res))))

(comment
  (dissoc {:a 1 :b 2} :a)
  (let [[first last](keep standardize-name ["sofiane" "larbi"])]
    [first last])
  (time (random/rand-num-str 6))
  (handle-signup-request "sofiane" "larbi" ""))


(defn verify-code [uid phone attempt-code]
  (let [[[true-code true-uid]] (d/q
                              `[:find ?code ?uid
                                :where
                                [?e :incomplete-user/phone ~phone]
                                [?e :incomplete-user/id ?uid]
                                [?e :incomplete-user/verify-code ?code]]
                              (d/db db/conn))]
    (when (= [uid attempt-code] [true-uid true-code]) 
      (let [new-uid (gen-auth-token)]
        (future
          (d/transact db/conn {:tx-data [[:db/add [:incomplete-user/phone phone] :incomplete-user/id new-uid]
                                         [:db/add [:incomplete-user/phone phone] :incomplete-user/verified true]]}))
        new-uid))))

(comment
  (d/q
   `[:find ?code ?uid
     :where
     [?e :incomplete-user/phone "+16503704040"]
     [?e :incomplete-user/id ?uid]
     [?e :incomplete-user/verify-code ?code]]
   (d/db db/conn))
  
  (verify-code "76e558ff-a776-493b-a7a0-84255aa5d3df" "+16462203750" "957755")
  (let [phone "+16462203750"
        [[true-code true-uid]] (d/q
                              `[:find ?code ?uid
                                :where
                                [?e :incomplete-user/phone ~phone]
                                [?e :incomplete-user/id ?uid]
                                [?e :incomplete-user/verify-code ?code]]
                              (d/db db/conn))]
    [true-code true-uid])
  (d/q
   `[:find ?uid
     :where
     [?e :incomplete-user/phone "+16462203750"]
     [?e :incomplete-user/id ?uid]]
   (d/db db/conn)))

(defn set-profile-picture [user-id f]
  (let [filename (str (gen-auth-token) ".png")
        url (store/upload-bytes filename f)]
    (d/transact db/conn {:tx-data [[:db/add [:user/id user-id] :user/profile-pic (new java.net.URI url)]]})
    url))

(comment
  "https://superlatives-files.s3.amazonaws.com/ooc-kk.jpg"
  (d/transact db/conn {:tx-data [[:db/add [:user/id "0b9269b2-a584-4537-9d04-a62471565127"] :user/profile-pic (new java.net.URI "https://superlatives-files.s3.amazonaws.com/ooc-kk.jpg")]]}))

(defn set-pre-profile-picture [phone uid f]
  (let [[[true-uid]] (d/q
                  `[:find ?uid
                    :where
                    [?e :incomplete-user/phone ~phone]
                    [?e :incomplete-user/id ?uid]]
                  (d/db db/conn))]
    (when (= true-uid uid)
      (let [filename (str (gen-auth-token) ".png")
            url (store/upload-bytes filename f) ; Future this
            new-uid (gen-auth-token)]
        (future 
          (d/transact db/conn {:tx-data [[:db/add [:incomplete-user/phone phone] :incomplete-user/id new-uid]
                                        [:db/add [:incomplete-user/phone phone] :incomplete-user/profile-pic url]]}))
        [new-uid url]))))

(comment
  (let [url (new java.net.URI "https://sofianelarbi.com")]
    (-> url .toString))
  (d/q
   `[:find ?uid
     :where
     [?e :incomplete-user/phone "+16462203750"]
     [?e :incomplete-user/id ?uid]]
   (d/db db/conn))
  (set-pre-profile-picture "+16462203750" "464eb063-c91e-45d5-bdae-3f01db6eb303" ""))


(defn matriculate-user [id phone password]
  (let [[[true-uid]] (d/q
                      `[:find ?uid
                        :where
                        [?e :incomplete-user/phone ~phone]
                        [?e :incomplete-user/id ?uid]]
                      (d/db db/conn))]
    (if (= id true-uid)
      (let [[[first-name last-name phone profile-pic verified]] (d/q
                                                        `[:find ?fname ?lname ?phone ?profilePic ?verified
                                                          :where
                                                          [?e :incomplete-user/id ~true-uid]
                                                          [?e :incomplete-user/first-name ?fname]
                                                          [?e :incomplete-user/last-name ?lname]
                                                          [?e :incomplete-user/phone ?phone]
                                                          [?e :incomplete-user/profile-pic ?profilePic]
                                                          [?e :incomplete-user/verified ?verified]]
                                                        (d/db db/conn))]
        (create-user! password phone first-name last-name profile-pic verified))
      (throw+ "Invalid credentials"))))

(comment
  (let  [[[phone first-name last-name profile-pic]] (d/q
   `[:find ?lname ?phone ?code ?verified ?id
     :where
     ;[?e :incomplete-user/id _]
     [?e :incomplete-user/first-name "Sofiane"]
     [?e :incomplete-user/last-name ?lname]
     [?e :incomplete-user/phone ?phone]
     [?e :incomplete-user/verify-code ?code]
     [?e :incomplete-user/verified ?verified]
     [?e :incomplete-user/id ?id]]
   (d/db db/conn))]
    [phone first-name last-name profile-pic]))

(defn set-device-token [user-id device-token]
  (d/transact db/conn {:tx-data [[:db/add [:user/id user-id] :user/device-token device-token]]}))

(defn delete-user! [user-id]
  (d/transact db/conn {:tx-data [[:db/retractEntity [:user/id user-id]]]}))

(comment
  (d/transact db/conn {:tx-data [[:db/retractEntity [:user/phone "+17578796726"]]]})
  (delete-user! "960522a9-6769-46ff-aa63-bf7de113a7da"))

