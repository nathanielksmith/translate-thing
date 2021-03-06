; SeaThree, Realtime Twitter Translations
; Copyright (C) 2014 Nathaniel Smith and Benjamin Valentine
;
; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU Affero General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; This program is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU Affero General Public License for more details.
;
; You should have received a copy of the GNU Affero General Public License
; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns seathree.test.data
  (:require [clojure.test          :refer :all    ]
            [seathree.test.helpers :refer :all    ]
            [cheshire.core         :as json       ]
            [clj-http.client       :as http-client]
            [clj-time.core         :as time       ]
            [clj-time.format       :as tfmt       ]
            [taoensso.carmine      :as car        ]
            [twitter.oauth         :as oauth      ]
            [twitter.api.restful   :as twitter    ]
            [seathree.data         :as data       ]))

(def user-data {:username "nate_smith" :src "en" :tgt "es"})
(def google-response {:body   (json/generate-string {:data {:translations [{:translatedText "hello"}]}})
                      :status 200})

(deftest process-failed?
  (testing "process failed"
    (is (= true (data/process-failed? {:exit 1}))))

  (testing "process didn't fail"
    (is (= false (data/process-failed? {:exit 0})))))

(deftest match
  (testing "returns true for a match"
    (is (= true (data/match #"^foob.*az$" "foobarbaaaaaz"))))
  (testing "returns false for not a match"
    (is (= false (data/match #"^foob.*az$" "foobarbz")))))

(deftest with-retries
  (testing "ignores retries < 0"
    (let [call-count (atom 0)
          fn         #(swap! call-count inc)
          result     (data/with-retries -1 fn)]
      (is (nil? result))
      (is (= 0 @call-count))))
  (testing "does nothing for 0"
    (let [call-count (atom 0)
          fn         #(swap! call-count inc)
          result     (data/with-retries 0 fn)]
      (is (nil? result))
      (is (= 0 @call-count))))
  (testing "for > 0"
    (testing "retries correct number of times and returns proper value"
      (let [call-count (atom 0)
            fn         (fn [] (swap! call-count inc) (if (= @call-count 4) :foo nil))
            result     (data/with-retries 10 fn)]
        (is (= result :foo))
        (is (= @call-count 4))))))

(deftest building-twitter-creds
  (testing "calls oauth function with proper args in proper order"
    (let [call-args  (atom [])
          mock-oauth (fn [& args] (swap! call-args (fn [_] args)))
          mock-cfg   {:twitter {:oauth {:access-token "789"
                                        :consumer-secret "456"
                                        :consumer-key "123"
                                        :access-token-secret "000"}}}]
      (with-redefs [oauth/make-oauth-creds mock-oauth]
        (data/twitter-creds-from-cfg mock-cfg)
        (is (= @call-args ["123" "456" "789" "000"]))))))

(deftest gen-key'
  (is (= "tweets_nate_smith_en_es" (data/gen-key' :tweets user-data))))

(deftest store-ts!
  (testing "with timestamp"
    (let [call-args    (atom [])
          redis-called (atom false)
          now-called   (atom false)]
      (with-redefs [data/redis   (fn [_ fn] (swap! redis-called true-fn) (fn))
                    time/now     (fn [] (swap! now-called true-fn))
                    tfmt/unparse (fn [f t] t)
                    car/set      (fn [k v] (swap! call-args (fn-lift [k v])))]
        (data/store-ts! {} (fn-lift "foo") {} "barbaz")
        (is (= true @redis-called))
        (is (= false @now-called))
        (is (= @call-args ["foo" "barbaz"])))))
  (testing "without timestamp"
    (let [call-args    (atom [])
          redis-called (atom false)
          now-called   (atom false)]
      (with-redefs [data/redis   (fn [_ fn] (swap! redis-called true-fn) (fn))
                    time/now     (fn [] (swap! now-called true-fn) "barbaz")
                    tfmt/unparse (fn [f t] t)
                    car/set      (fn [k v] (swap! call-args (fn-lift [k v])))]
        (data/store-ts! {} (fn-lift "foo") {})
        (is (= true @redis-called))
        (is (= true @now-called))
        (is (= @call-args ["foo" "barbaz"]))))))

(deftest get-ts
  (testing "handles non-nil"
    (with-redefs [time/minus (fn-lift "foo")
                  tfmt/parse (fn-lift "bar")
                  data/redis (fn-lift "ts")]
      (let [result (data/get-ts _ (fn-lift "baz") _)]
        (is (= result "bar")))))
  (testing "handles nil"
    (with-redefs [time/minus (fn-lift "foo")
                  tfmt/parse (fn-lift "bar")
                  data/redis nil-fn]
      (let [result (data/get-ts _ (fn-lift "baz") _)]
        (is (= result "foo"))))))

(deftest get-tweets-from-cache
  (let [call-args (atom [])]
    (with-redefs [data/redis (fn [_ f] (f))
                  car/llen   (fn-lift 100)
                  car/lrange (fn [& args] (swap! call-args (fn-lift args)) [:tweets-here])]
      (let [result (data/get-tweets-from-cache _ user-data)]
        (is (= @call-args ["tweets_nate_smith_en_es" 0 100]))
        (is (= result (assoc user-data :tweets [:tweets-here])))))))

(deftest refresh-tweets!
  (let [now           (time/now)
        stale         (time/minutes 5)] ;; pin this so tests can hardcode against 5
    (testing "when data is fresh"
      (let [redis-called (atom false)
            now-called   (atom false)]
        (with-redefs [data/stale  stale 
                      data/get-ts (fn-lift (time/minus now (time/minutes 1)))
                      time/now    (fn [] (swap! now-called true-fn) now)
                      data/redis  (fn [_ _] (swap! redis-called true-fn))]
          @(data/refresh-tweets! _ user-data)
          (is (= false @redis-called) "redis is not called")
          (is (= true @now-called)))))

    (testing "when data is stale"
      (let [store-ts-called (atom false)
            lpush-args      (atom [])]
      (with-redefs [data/stale                   stale
                    data/get-ts                  (fn-lift (time/minus now (time/minutes 10)))
                    time/now                     (fn-lift now)
                    data/redis                   (fn [_ f] (f))
                    data/store-ts!               (fn [_ _ _] (swap! store-ts-called true-fn))
                    car/lindex                   (fn [_ _] {:id 123})
                    car/lpush                    (fn [_ tweets] (swap! lpush-args (fn-lift tweets)))
                    data/get-tweets-from-twitter (fn-lift ["one" "two" "three"])
                    data/translate               (fn-lift "four")]
        @(data/refresh-tweets! _ user-data)
        (is (= true @store-ts-called))
        (is (= @lpush-args ["four" "four" "four"])))))

    (testing "when twitter API fails"
      (let [translate-called (atom false)
            twitter-called   (atom false)]
        (with-redefs [data/get-ts                  nil-fn
                      data/store-ts!               nil-fn
                      time/before?                 true-fn
                      data/redis                   nil-fn
                      data/get-tweets-from-twitter (fn [_ _ _] (swap! twitter-called true-fn) nil)
                      data/translate               (fn [_ _ _] (swap! translate-called true-fn))]
          @(data/refresh-tweets! _ user-data)
          (is (= true @twitter-called)) "twitter is called")
          (is (= false @translate-called) "translate is not called")))

    (testing "when translation fails"
      (let [translate-called (atom false)
            lindex-called    (atom false)
            lpush-called     (atom false)]
        (with-redefs [data/get-ts                  nil-fn
                      data/store-ts!               nil-fn
                      time/before?                 true-fn
                      data/redis                   (fn [_ f] (f))
                      car/lindex                   (fn [_ _] (swap! lindex-called true-fn) nil)
                      data/get-tweets-from-twitter (fn-lift ["hi" "there" "you"])
                      data/translate               (fn [_ _ _] (swap! translate-called true-fn) nil)
                      car/lpush                    (fn [_ _] (swap! lpush-called true-fn))]
          @(data/refresh-tweets! _ user-data)
          (is (= true @lindex-called))
          (is (= true @translate-called))
          (is (= false @lpush-called)))))))

(deftest mk-sigil
  (is (= "XZ0" (data/mk-sigil 0))))

(deftest extract-translation
  (is (= "hello" (data/extract-translation google-response))))

(deftest mark-sigils
  (is (= ["XZ0 hello XZ1 guys XZ2 how" ["@joz" "#there" "http://foobar.com"]]
         (data/mark-sigils "@joz hello #there guys http://foobar.com how"))))

(deftest restore-sigils
  (is (=  "@joz hello #there guys http://foobar.com how"
          (data/restore-sigils "XZ0 hello XZ1 guys XZ2 how" ["@joz" "#there" "http://foobar.com"]))))
                                                    
(deftest translate
  (testing "proper query passed to get"
    (let [get-opts (atom {})]
      (with-redefs [http-client/get (fn [_ opts] (swap! get-opts (fn-lift opts)))]
        (data/translate {:google {:key "foo"}} user-data "hello")
        (is (= @get-opts {:query-params {"key" "foo" "source" "en" "target" "es" "q" "hello"}})))))

  (testing "when google fails"
    (let [get-called (atom false)]
      (with-redefs [http-client/get (fn [& a] (swap! get-called true-fn) {:status 400})]
        (let [result (data/translate _ user-data "hello")]
          (is (= @get-called true))
          (is (= result nil))))))

  (testing "when google succeeds"
    (with-redefs [http-client/get (fn-lift google-response)]
      (let [result (data/translate _ user-data "hi")]
        (is (= result "hello"))))))

(deftest get-tweets-from-twitter
  (testing "when twitter fails"
    (let [twitter-called (atom false)]
      (with-redefs [twitter/statuses-user-timeline (fn [& a]
                                                     (swap! twitter-called true-fn)
                                                     {:status {:code 403}})
                    data/twitter-creds-from-cfg    nil-fn]
        (let [result (data/get-tweets-from-twitter _ user-data)]
          (is (= @twitter-called true))
          (is (= result nil))))))

  (testing "when twitter suceeeds"
    (with-redefs [twitter/statuses-user-timeline (fn [& a]
                                                   {:status {:code 20}
                                                    :body [{:text "hi"}
                                                           {:text "there"}
                                                           {:text "how"}]})
                  data/twitter-creds-from-cfg    nil-fn]
      (let [result (data/get-tweets-from-twitter _ user-data)]
        (is (= result ["hi" "there" "how"])))))

  (testing "twitter is sent proper arguments"
    (testing "with last-tweet-id"
    (let [twitter-args (atom [])]
      (with-redefs [twitter/statuses-user-timeline (fn [& a]
                                                     (swap! twitter-args (fn-lift a))
                                                     {:status {:code 403}})
                    data/twitter-creds-from-cfg (fn-lift "oauth-creds")]
        (data/get-tweets-from-twitter _ user-data 123)
        (is (= @twitter-args [:oauth-creds "oauth-creds" :params {:since-id 123 :screen-name "nate_smith" :include-rts false}])))))
        
    (testing "without last-tweet-id"
      (let [twitter-args (atom [])]
        (with-redefs [twitter/statuses-user-timeline (fn [& a]
                                                       (swap! twitter-args (fn-lift a))
                                                       {:status {:code 403}})
                      data/twitter-creds-from-cfg (fn-lift "oauth-creds")]
          (data/get-tweets-from-twitter _ user-data)
          (is (= @twitter-args [:oauth-creds "oauth-creds" :params {:screen-name "nate_smith" :include-rts false}])))))))
