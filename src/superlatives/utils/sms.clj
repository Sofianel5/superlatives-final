(ns superlatives.utils.sms
  (:require [twijio.core :as twilio]))

(def from-number "+17064206906")

(def account-sid (System/getenv "TWILIO_SID"))

(def auth-token (System/getenv "TWILIO_AUTH"))

(def config
  {:twilio-account account-sid
   :twilio-token auth-token})

(defn send-sms [phone message]
  (twilio/send-sms! from-number phone message config))

(comment
  (send-sms "+19177569151" "420 69 lol"))