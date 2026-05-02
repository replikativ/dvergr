(ns dvergr.web.calendar
  "Web UI for the company calendar.

   Provides:
   - FullCalendar.js-based interactive calendar view (month/week/day)
   - JSON API for CRUD operations (FullCalendar-compatible event format)
   - Dark theme matching dvergr's existing palette

   Routes (mounted in web.server):
     GET  /calendar                      — full calendar page
     GET  /api/calendar/events           — JSON events (FullCalendar feed)
     POST /api/calendar/events           — create event
     PUT  /api/calendar/events/:id       — update event
     DELETE /api/calendar/events/:id     — delete event"
  (:require [hiccup2.core :as h]
            [hiccup.util :as hu]
            [dvergr.calendar.core :as cal]
            [dvergr.web.layout :as layout]
            [jsonista.core :as json]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone UUID]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private conn-a (atom nil))

(defn init!
  "Store the shared Datahike connection. Call once at startup."
  [datahike-conn]
  (reset! conn-a datahike-conn))

;; ============================================================================
;; JSON helpers
;; ============================================================================

(def ^:private json-mapper
  (json/object-mapper {:encode-key-fn name
                       :decode-key-fn keyword}))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/write-value-as-string body json-mapper)})

(defn- parse-json-body [req]
  (when-let [body (:body req)]
    (let [s (if (string? body)
              body
              (slurp body))]
      (json/read-value s json-mapper))))

(defn- iso-format [^Date d]
  (when d
    (let [fmt (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")]
      (.setTimeZone fmt (TimeZone/getTimeZone "UTC"))
      (.format fmt d))))

(defn- parse-iso [s]
  (when (and s (not (str/blank? s)))
    (try
      (let [clean (str/replace s #"Z$" "")
            fmt (if (> (count clean) 10)
                  (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
                  (SimpleDateFormat. "yyyy-MM-dd"))]
        (.setTimeZone fmt (TimeZone/getTimeZone "UTC"))
        (.parse fmt clean))
      (catch Exception _ nil))))

;; ============================================================================
;; Event color mapping
;; ============================================================================

(defn- event-color [evt]
  (case (or (:cal/type evt) :external)
    :meeting    "#667eea"
    :discussion "#764ba2"
    :deadline   "#e67e22"
    :review     "#27ae60"
    :external   "#666"
    "#667eea"))

(defn- event->fullcalendar
  "Convert a Datahike calendar event to FullCalendar JSON format."
  [evt]
  {:id    (str (:cal/id evt))
   :title (:cal/title evt)
   :start (iso-format (:cal/start evt))
   :end   (iso-format (:cal/end evt))
   :color (event-color evt)
   :extendedProps {:type        (some-> (:cal/type evt) name)
                   :source      (some-> (:cal/source evt) name)
                   :location    (:cal/location evt)
                   :description (:cal/description evt)
                   :status      (some-> (:cal/status evt) name)
                   :created-by  (:cal/created-by evt)
                   :participants (mapv name (or (:cal/participants evt) []))}})

;; ============================================================================
;; CSS
;; ============================================================================

(def ^:private css
  "/* Calendar page styles — dark theme */
   .calendar-container {
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 8px;
     padding: 16px;
     margin-top: 1em;
   }

   /* FullCalendar dark theme overrides */
   .fc { --fc-bg-color: #12122a; --fc-border-color: #2a2a4a; }
   .fc .fc-button { background: #1a1a2e; border-color: #2a2a4a; color: #ccc; font-size: 0.85em; }
   .fc .fc-button:hover { background: #252550; border-color: #667eea; }
   .fc .fc-button-active { background: #1e1040 !important; border-color: #667eea !important; color: #a78bfa !important; }
   .fc .fc-toolbar-title { color: #ccc; font-size: 1.2em; }
   .fc .fc-col-header-cell { background: #1a1a2e; }
   .fc .fc-col-header-cell-cushion { color: #888; }
   .fc .fc-daygrid-day-number { color: #888; }
   .fc .fc-daygrid-day.fc-day-today { background: #1e1040 !important; }
   .fc .fc-timegrid-slot { border-color: #1a1a2e; }
   .fc .fc-timegrid-slot-label-cushion { color: #555; font-size: 0.8em; }
   .fc .fc-event { border: none; border-radius: 4px; font-size: 0.82em; padding: 1px 4px; cursor: pointer; }
   .fc .fc-event:hover { opacity: 0.85; }
   .fc .fc-scrollgrid { border-color: #2a2a4a; }
   .fc td, .fc th { border-color: #1e1e3a; }
   .fc .fc-more-link { color: #667eea; }
   .fc .fc-popover { background: #1a1a2e; border-color: #2a2a4a; }
   .fc .fc-popover-header { background: #252550; color: #ccc; }

   /* Event modal */
   .modal-overlay {
     display: none;
     position: fixed; top: 0; left: 0; width: 100%; height: 100%;
     background: rgba(0,0,0,0.7); z-index: 1000;
     justify-content: center; align-items: center;
   }
   .modal-overlay.active { display: flex; }
   .modal-content {
     background: #1a1a2e;
     border: 1px solid #2a2a4a;
     border-radius: 12px;
     padding: 24px;
     width: 440px;
     max-width: 90vw;
     max-height: 80vh;
     overflow-y: auto;
   }
   .modal-content h3 {
     color: #ccc;
     margin-top: 0;
     margin-bottom: 16px;
   }
   .modal-content label {
     display: block;
     color: #888;
     font-size: 0.82em;
     margin-bottom: 3px;
     margin-top: 10px;
   }
   .modal-content input, .modal-content select, .modal-content textarea {
     width: 100%;
     padding: 6px 10px;
     background: #12122a;
     border: 1px solid #2a2a4a;
     border-radius: 6px;
     color: #ccc;
     font-size: 0.9em;
     box-sizing: border-box;
   }
   .modal-content textarea { height: 60px; resize: vertical; }
   .modal-content input:focus, .modal-content select:focus, .modal-content textarea:focus {
     border-color: #667eea;
     outline: none;
   }
   .modal-actions {
     margin-top: 16px;
     display: flex;
     gap: 8px;
     justify-content: flex-end;
   }
   .btn { padding: 6px 16px; border-radius: 6px; border: 1px solid #2a2a4a; cursor: pointer; font-size: 0.85em; }
   .btn-primary { background: #667eea; color: #fff; border-color: #667eea; }
   .btn-primary:hover { background: #5a6fd6; }
   .btn-danger { background: #c0392b; color: #fff; border-color: #c0392b; }
   .btn-danger:hover { background: #a93226; }
   .btn-secondary { background: #1a1a2e; color: #ccc; }
   .btn-secondary:hover { background: #252550; }")

;; ============================================================================
;; Page
;; ============================================================================

(defn calendar-page
  "Render the calendar HTML page with FullCalendar.js."
  []
  (layout/page-chrome
    {:title "Calendar" :active-page :calendar :extra-css css :htmx? false}

    [:h1 "Calendar"]

    ;; Modal for create/edit
    [:div#event-modal.modal-overlay
     [:div.modal-content
      [:h3#modal-title "New Event"]
      [:input#modal-event-id {:type "hidden"}]
      [:label "Title"]
      [:input#modal-title-input {:type "text" :placeholder "Event title"}]
      [:label "Start"]
      [:input#modal-start {:type "datetime-local"}]
      [:label "End"]
      [:input#modal-end {:type "datetime-local"}]
      [:label "Type"]
      [:select#modal-type
       [:option {:value "meeting"} "Meeting"]
       [:option {:value "discussion"} "Discussion"]
       [:option {:value "deadline"} "Deadline"]
       [:option {:value "review"} "Review"]
       [:option {:value "external"} "External"]]
      [:label "Location"]
      [:input#modal-location {:type "text" :placeholder "Optional"}]
      [:label "Description"]
      [:textarea#modal-description {:placeholder "Optional"}]
      [:label "Participants (comma-separated agent IDs)"]
      [:input#modal-participants {:type "text" :placeholder "e.g. volva, muninn, huginn"}]
      [:div.modal-actions
       [:button#btn-delete.btn.btn-danger {:style "display:none"} "Delete"]
       [:button#btn-cancel.btn.btn-secondary "Cancel"]
       [:button#btn-save.btn.btn-primary "Save"]]]]

    ;; Calendar container
    [:div.calendar-container
     [:div#calendar]]

    ;; FullCalendar.js from CDN
    [:script {:src "https://cdn.jsdelivr.net/npm/fullcalendar@6.1.15/index.global.min.js"}]

    ;; Calendar initialization
    [:script
     (hu/raw-string
       "document.addEventListener('DOMContentLoaded', function() {
          var calendarEl = document.getElementById('calendar');
          var modal = document.getElementById('event-modal');

          var calendar = new FullCalendar.Calendar(calendarEl, {
            initialView: 'dayGridMonth',
            headerToolbar: {
              left: 'prev,next today',
              center: 'title',
              right: 'dayGridMonth,timeGridWeek,timeGridDay'
            },
            themeSystem: 'standard',
            height: 'auto',
            nowIndicator: true,
            editable: true,
            selectable: true,
            selectMirror: true,
            dayMaxEvents: true,
            firstDay: 1,

            events: {
              url: '/api/calendar/events',
              failure: function() {
                console.error('Failed to load calendar events');
              }
            },

            // Click on empty slot → create
            select: function(info) {
              openModal('create', {
                start: toLocalISO(info.start),
                end: toLocalISO(info.end || info.start)
              });
              calendar.unselect();
            },

            // Click on event → edit
            eventClick: function(info) {
              var evt = info.event;
              var props = evt.extendedProps || {};
              openModal('edit', {
                id: evt.id,
                title: evt.title,
                start: toLocalISO(evt.start),
                end: toLocalISO(evt.end || evt.start),
                type: props.type || 'meeting',
                location: props.location || '',
                description: props.description || '',
                participants: (props.participants || []).join(', ')
              });
            },

            // Drag-and-drop reschedule
            eventDrop: function(info) {
              var evt = info.event;
              fetch('/api/calendar/events/' + evt.id, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                  start: evt.start.toISOString(),
                  end: (evt.end || evt.start).toISOString()
                })
              }).then(function(r) {
                if (!r.ok) { info.revert(); }
              });
            },

            // Resize
            eventResize: function(info) {
              var evt = info.event;
              fetch('/api/calendar/events/' + evt.id, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                  start: evt.start.toISOString(),
                  end: (evt.end || evt.start).toISOString()
                })
              }).then(function(r) {
                if (!r.ok) { info.revert(); }
              });
            }
          });

          calendar.render();

          // Helper: convert Date to local ISO for datetime-local input
          function toLocalISO(d) {
            if (!d) return '';
            var dt = new Date(d);
            var offset = dt.getTimezoneOffset();
            var local = new Date(dt.getTime() - offset * 60000);
            return local.toISOString().slice(0, 16);
          }

          // Modal open
          function openModal(mode, data) {
            document.getElementById('modal-title').textContent = mode === 'create' ? 'New Event' : 'Edit Event';
            document.getElementById('modal-event-id').value = data.id || '';
            document.getElementById('modal-title-input').value = data.title || '';
            document.getElementById('modal-start').value = data.start || '';
            document.getElementById('modal-end').value = data.end || '';
            document.getElementById('modal-type').value = data.type || 'meeting';
            document.getElementById('modal-location').value = data.location || '';
            document.getElementById('modal-description').value = data.description || '';
            document.getElementById('modal-participants').value = data.participants || '';
            document.getElementById('btn-delete').style.display = mode === 'edit' ? 'inline-block' : 'none';
            modal.classList.add('active');
          }

          // Modal close
          document.getElementById('btn-cancel').addEventListener('click', function() {
            modal.classList.remove('active');
          });
          modal.addEventListener('click', function(e) {
            if (e.target === modal) modal.classList.remove('active');
          });

          // Save (create or update)
          document.getElementById('btn-save').addEventListener('click', function() {
            var id = document.getElementById('modal-event-id').value;
            var participants = document.getElementById('modal-participants').value
              .split(',').map(function(s) { return s.trim(); }).filter(function(s) { return s; });
            var body = {
              title: document.getElementById('modal-title-input').value,
              start: new Date(document.getElementById('modal-start').value).toISOString(),
              end: new Date(document.getElementById('modal-end').value).toISOString(),
              type: document.getElementById('modal-type').value,
              location: document.getElementById('modal-location').value,
              description: document.getElementById('modal-description').value,
              participants: participants
            };
            var url = id ? '/api/calendar/events/' + id : '/api/calendar/events';
            var method = id ? 'PUT' : 'POST';
            fetch(url, {
              method: method,
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify(body)
            }).then(function(r) {
              if (r.ok) {
                modal.classList.remove('active');
                calendar.refetchEvents();
              } else {
                r.json().then(function(d) { alert(d.error || 'Failed to save'); });
              }
            });
          });

          // Delete
          document.getElementById('btn-delete').addEventListener('click', function() {
            var id = document.getElementById('modal-event-id').value;
            if (id && confirm('Delete this event?')) {
              fetch('/api/calendar/events/' + id, { method: 'DELETE' })
                .then(function(r) {
                  if (r.ok) {
                    modal.classList.remove('active');
                    calendar.refetchEvents();
                  }
                });
            }
          });
        });")]))

;; ============================================================================
;; API Handlers
;; ============================================================================

(defn api-get-events
  "GET /api/calendar/events?start=...&end=...
   Returns FullCalendar-compatible JSON array."
  [req]
  (if-let [conn @conn-a]
    (let [params (some-> (:query-string req) (str/split #"&") (->> (into {} (map #(let [[k v] (str/split % #"=" 2)] [k v])))))
          start (or (parse-iso (get params "start")) (Date.))
          end (or (parse-iso (get params "end")) (cal/from-now {:days 31}))]
      (let [events (cal/events-between conn start end)]
        (json-response 200 (mapv event->fullcalendar events))))
    (json-response 200 [])))

(defn api-create-event
  "POST /api/calendar/events — create a new event."
  [req]
  (if-let [conn @conn-a]
    (try
      (let [body (parse-json-body req)
            participants (when (seq (:participants body))
                           (mapv keyword (:participants body)))
            evt (cal/add-event! conn
                  (cond-> {:title (:title body)
                           :start (parse-iso (:start body))
                           :end   (parse-iso (:end body))
                           :type  (keyword (or (:type body) "meeting"))
                           :created-by "human"}
                    (seq participants) (assoc :participants participants)
                    (not (str/blank? (:location body))) (assoc :location (:location body))
                    (not (str/blank? (:description body))) (assoc :description (:description body))))]
        (json-response 201 (event->fullcalendar evt)))
      (catch Exception e
        (json-response 400 {:error (.getMessage e)})))
    (json-response 503 {:error "Calendar not initialized"})))

(defn api-update-event
  "PUT /api/calendar/events/:id — update an event."
  [req event-id-str]
  (if-let [conn @conn-a]
    (try
      (let [event-id (UUID/fromString event-id-str)
            body (parse-json-body req)
            updates (cond-> {}
                      (:title body)       (assoc :cal/title (:title body))
                      (:start body)       (assoc :cal/start (parse-iso (:start body)))
                      (:end body)         (assoc :cal/end (parse-iso (:end body)))
                      (:type body)        (assoc :cal/type (keyword (:type body)))
                      (:location body)    (assoc :cal/location (:location body))
                      (:description body) (assoc :cal/description (:description body))
                      (:status body)      (assoc :cal/status (keyword (:status body)))
                      (seq (:participants body))
                      (assoc :cal/participants (set (mapv keyword (:participants body)))))]
        (cal/update-event! conn event-id updates)
        (let [updated (cal/get-event conn event-id)]
          (json-response 200 (event->fullcalendar updated))))
      (catch Exception e
        (json-response 400 {:error (.getMessage e)})))
    (json-response 503 {:error "Calendar not initialized"})))

(defn api-delete-event
  "DELETE /api/calendar/events/:id — delete an event."
  [_req event-id-str]
  (if-let [conn @conn-a]
    (try
      (let [event-id (UUID/fromString event-id-str)]
        (cal/delete-event! conn event-id)
        (json-response 200 {:deleted event-id-str}))
      (catch Exception e
        (json-response 400 {:error (.getMessage e)})))
    (json-response 503 {:error "Calendar not initialized"})))

;; ============================================================================
;; Route Dispatcher
;; ============================================================================

(defn api-handler
  "Dispatch /api/calendar/* requests."
  [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      ;; GET /api/calendar/events
      (and (= method :get) (= uri "/api/calendar/events"))
      (api-get-events req)

      ;; POST /api/calendar/events
      (and (= method :post) (= uri "/api/calendar/events"))
      (api-create-event req)

      ;; PUT /api/calendar/events/:id
      (and (= method :put)
           (str/starts-with? uri "/api/calendar/events/"))
      (let [id (subs uri (count "/api/calendar/events/"))]
        (api-update-event req id))

      ;; DELETE /api/calendar/events/:id
      (and (= method :delete)
           (str/starts-with? uri "/api/calendar/events/"))
      (let [id (subs uri (count "/api/calendar/events/"))]
        (api-delete-event req id))

      ;; OPTIONS (CORS preflight)
      (= method :options)
      {:status 204
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET,POST,PUT,DELETE,OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type"}}

      :else
      (json-response 404 {:error "Not found"}))))
