(ns superlatives.utils.random)

(defn rand-num-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 10) 48))))))

(defn rand-str [len]
  ; Doesn't include capital I and lowercase l to avoid confusion
  (apply str (repeatedly len #(rand-nth "ABCDEFGHJKLMNOPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0123456789"))))

(comment
  (rand-num-str 6)
  (rand-str 6))