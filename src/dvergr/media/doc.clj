(ns dvergr.media.doc
  "Document text extraction for agents (first: PDF via PDFBox).
   Sandbox surface: `doc/extract-text` (dvergr.sandbox.ns.io) reads a
   path through the chat-ctx's muschel FS — so mounted drives work —
   and hands the bytes here."
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.text PDFTextStripper]))

(def ^:private ^:const max-chars (* 512 1024))

(defn pdf-text
  "Extract the text layer of a PDF (bytes) → string (truncated at
   512k chars). Scanned/image-only PDFs return empty text — route
   those through vision/describe per page instead."
  [^bytes bytes]
  (with-open [doc (Loader/loadPDF bytes)]
    (let [s (.getText (PDFTextStripper.) doc)]
      (if (> (count s) max-chars)
        (str (subs s 0 max-chars) "\n…[truncated]")
        s))))

(defn extract-text
  "Text from document bytes by mime. PDFs via PDFBox; text/* pass
   through; other formats → nil (unsupported)."
  [^bytes bytes mime]
  (cond
    (and mime (.contains ^String mime "pdf"))
    (pdf-text bytes)

    (and mime (or (.startsWith ^String mime "text/")
                  (re-find #"json|xml|csv|markdown" mime)))
    (String. bytes "UTF-8")

    :else nil))
