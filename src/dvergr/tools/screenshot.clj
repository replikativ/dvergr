(ns dvergr.tools.screenshot
  "Screenshot and VLM (Vision Language Model) tool.

   Takes a screenshot of the current display, sends it to a VLM for description,
   and returns the text description. Useful for agents that need visual feedback
   about UI state, desktop state, or other visual information."
  (:require [dvergr.tools :as tools]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Base64]
           [java.io File]))

(defn- take-screenshot!
  "Take a screenshot using available system tools. Returns the file path."
  [output-path]
  (let [file (io/file output-path)]
    ;; Try gnome-screenshot first, fall back to scrot, then import (ImageMagick)
    (let [result (loop [cmds [["gnome-screenshot" "-f" (str file)]
                              ["scrot" (str file)]
                              ["import" "-window" "root" (str file)]]]
                   (if-let [cmd (first cmds)]
                     (let [pb (ProcessBuilder. ^java.util.List (vec cmd))
                           proc (.start pb)
                           exit (.waitFor proc 10 java.util.concurrent.TimeUnit/SECONDS)]
                       (if (and exit (zero? (.exitValue proc)) (.exists file))
                         (str file)
                         (recur (rest cmds))))
                     nil))]
      (or result
          (throw (ex-info "No screenshot tool available (tried gnome-screenshot, scrot, import)"
                          {:output-path output-path}))))))

(defn- file->base64
  "Read a file and return its base64 encoding."
  [path]
  (let [bytes (-> (io/file path) io/input-stream .readAllBytes)]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- describe-image-with-vlm
  "Send a base64 image to a VLM model for description."
  [base64-image prompt]
  ;; Lazy-load model.chat to avoid circular deps
  (require 'dvergr.model.chat)
  (let [chat-fn (ns-resolve (find-ns 'dvergr.model.chat) 'chat)
        messages [{:role "user"
                   :content [{:type "text"
                              :text (or prompt "Describe this screenshot in detail. What applications are visible? What is the user doing?")}
                             {:type "image_url"
                              :image_url {:url (str "data:image/png;base64," base64-image)}}]}]
        result (chat-fn {:provider :fireworks
                         :model "accounts/fireworks/models/llama-v3p2-90b-vision-instruct"
                         :messages messages
                         :max-tokens 1024})]
    (get-in result [:choices 0 :message :content])))

(tools/register!
  {:name "screenshot"
   :description "Take a screenshot of the current display and get a text description.

   Uses a Vision Language Model (VLM) to analyze the screenshot and describe
   what's visible. Useful for:
   - Checking if a UI rendered correctly
   - Understanding the current desktop state
   - Getting visual feedback about applications

   Parameters:
   - prompt: Custom prompt for the VLM (optional, default describes the screenshot)
   - save_path: Where to save the screenshot file (optional, default /tmp/dvergr-screenshot.png)

   Returns a text description of the screenshot.

   Example: Take and describe screenshot
   {}

   Example: Check specific UI element
   {\"prompt\": \"Is there a login form visible? What fields does it have?\"}"
   :parameters {:type "object"
                :properties {:prompt {:type "string"
                                      :description "Custom prompt for VLM analysis"}
                             :save_path {:type "string"
                                         :description "File path to save screenshot (default /tmp/dvergr-screenshot.png)"}}
                :required []}
   :execute (fn [{:keys [prompt save_path]} _ctx]
              (try
                (let [path (or save_path "/tmp/dvergr-screenshot.png")
                      screenshot-path (take-screenshot! path)
                      base64 (file->base64 screenshot-path)
                      description (describe-image-with-vlm base64 prompt)]
                  {:type :success
                   :content (str "Screenshot saved to: " screenshot-path "\n\n"
                                 "Description:\n" description)
                   :metadata {:path screenshot-path
                              :size (.length (io/file screenshot-path))
                              :description description}})
                (catch Exception e
                  {:type :error
                   :error (str "Screenshot failed: " (.getMessage e))})))})
