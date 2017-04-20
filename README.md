# Event Data Percolator

<img src="doc/percolator-render.png" align="right" style="float: right">

The Event Data Percolator identifies and extracts Events from inputs fed to it by Agents. It takes Input Bundles from Agents and creates Evidence Records and Events, and sends them to the Evidence Registry and Event Bus respectively. 

The Event Data Percolator accepts Input Bundles from Agents. An Input Bundle contains URLs, text, HTML and links found by an Agent, along with all the other information necessary to create Events and Evidence Records. The Agent therefore focuses on doing a specific job: connecting to its data source, gathering inputs and sending them to the Percolator in a recognised format.

![](doc/workflow.png)

The Percolator therefore does most of the work involved in finding Events. By having a decoupled service, the service and algorithms that extract data can be improved, upgraded and scaled independently of the Agents.

(Of course, implementors of Event Data Agents are free to use whatever methods are best. The Event Data Percolator implements common patterns observed when building Crossref's Event Data Agents.)

> NB: This is under development, details subject to change.

## Input Bundle format

An Agent identifies Actions. An Action represents a single actionable stimulus that led to an Event. Examples:

 - a tweet was published
 - a comment was published on a social media site
 - a blog post was published

There are a number of ways that observations can be made: a source may send chunks of HTML, or lists of URLs, or plain text. There are therefore corresponding observation types. Furthermore, some Actions may be observed more than one way: the Gnip URL sends both the text of the tweet and a list of URLs, and a Newsfeed Action can include both the Blog Post's URL and an HTML summary snippet. Each Action therefore has zero or more Observations.

Actions are packaged up in a structure that describes the structure by they were recieved. Some Triggers provide a single Action (e.g. a tweet), some present a list of Actions (e.g. a Newsfeed retrieval) and some present a list of pages of actions (e.g. a Reddit API response). A list of pages, each containing a list of Actions is the common denomenator. For simplicity, all Input Bundles must have this format.

An Input Bundle is a JSON object that packages up Actions and Observations, along with any extra required information.

Each Observation type is processed (every type has a different method), and DOIs identified, inferred or extracted. The Percolator takes an Input Bundle and, preserving its structure, processes each Observation into an Observation Output. The finished Output Bundle is identical to the Input Bundle, but with all Observations transformed.

An Input Bundle may also have a trigger, which describes the reason for the trigger. If the reason was an artifact-scan, the version of the artifact in use is also included. The Trigger information is purely for informational purposes; the content is not inspected by the Percolator.

## Example Input Bundles

### Twitter Input Bundle

A Twitter trigger is a single tweet. Therefore there's only one page with one Action, but that Action can contain both a `plain-text` observation (for the Tweet text) and potentially a number of `url`observations (as extracted and sent by the Gnip API).

![](doc/input-bundle-formats-twitter.png)

### Newsfeed Input Bundle

A Newsfeed trigger is the retrieval of a given RSS feed. A newsfeed usually contains one page of data, but each page has a number of published blog posts, each of which is an Action. The entry in an RSS feed can have two observations: the URL of the post and a summary HTML snippet.

![](doc/input-bundle-formats-newsfeed.png)

### Reddit Input Bundle

A Reddit trigger is a search query for a domain. The API will return a list of pages of posts. Each post has plain-text content.

![](doc/input-bundle-formats-reddit.png)

## Deduplication

An Action must have an ID. This is different to the finished Event ID. The Percolator will only process a trigger once, and if it is subsequently asked to process it, it will politely decline, including a "duplicate" field which includes the date and Evidence Record ID that the Event previously occurred on. Duplicates *within* an Evidence bundle are not detected. Don't send them.

The interpretation and formulation of the ID is up to the Agent:

 - the Twitter agent uses a hash of the tweet ID, ensuring each tweet can only be processed once. This is useful because it allows the Tweet stream to be re-processed with catch-up, with no duplicates introduced.
 - the Wikipedia agent uses a hash of the the external Event Stream ID, ensuring that the Wikipedia agent can be run in parallel during deployment transitions.
 - the Newsfeed agent uses a hash of the concatenation of the blog post URL, meaning the blog post can only be processed once, regardless of which feed it was seen on. This is useful because blog feeds repeatedly return previously seen data.
 - the Reddit agent uses a hash of the post ID. This is useful because the Reddit API can return posts previously seen.

## Workflow

The end-to-end workflow is as follows. Each is a discrete, self-contained step.

### 1. Accept Input Bundle

 - Check JWT authentication meets one of the secrets.

### 2. Queue

 - Input Bundles are placed on a queue, and dequeued.

### 3. Dedupe Actions

 - Transform Input Bundle, keeping structure identical.
 - Look up each Action ID.
 - If it has already been seen, set the "duplicate" field to the Event ID and date. 
 - If the Action ID has not already been seen, set the "duplicate" value to "false".

### 4. Process each Observation to extract Candidates

 - Transform Input Bundle, keeping structure identical.
 - According to the input type, apply the relevant transformation to generate candidates.
 - Each transformation is supplied the value of the "duplicate" value.
 - If it is "false", the transformation is applied normally.
 - If it is not false, the transformation won't attempt to extract any DOIs, but will pass through the input (or a hash of it).

### 5. Match Candidates into DOIs

 - Transform Input Bundle, keeping structure identical
 - For every Action:
     - Collect all of the candidate DOIs and Landing Page URLs
     - Attempt to convert each one into a DOI 
 - Also 5.5: Deduplicate matches that identify the same DOI from the same input but via different candidates. E.g. a hyperlinked DOI with the DOI also in the link text.

### 6. Create Events and Evidence Record

 - Generate an Evidence Record ID (a UUID).
 - Create an Evidence Record that includes the resulting Input Package under the "input" key
 - For every Action, take the union of DOIs found (as some may be found by more than one Observation).
 - Create a mapping of Action ID to list of Events, include in the Evidence Record under the "events" key.

### 7. Send

 - Send Evidence Record to Evidence Registry with its ID.
 - Send each Event to the Event Bus.
 - Register each Action ID to prevent future duplicates.
 
 ## Details of Input Package format
 
 ### Input Package
 
Required fields:

 - `source-name` - the name of the source, e.g. `wikipedia`.
 - `source-token` - the unique ID of the agent collecting data.
 - `pages` - list of page objects

Optional fields:
 - `license` - a license URL, attached to each Event
 
Other fields may be supplied by the Agent if required, and will be carried through.
 
## Bundle Format

Schema documented in `event-data-percolator.input-bundle` namespace.

### Input Bundle

Required fields:

 - `pages` - a list of pages

Optional fields:

 - `trigger` - trigger information

### Trigger

Required fields:

 - `type` - one of `external-event`, `artifact-scan`, `batch-file`

Optional fields:

 - `artifact-version` - the version ID of the artifact that was used for the artifact scan

### Page Object

Required fields:

 - `actions` - list of Actions

Optional Fields:

 - `url` - the URL that gave rise to this page 
 
Other fields may be supplied by the Agent if required, and will be carried through.

### Action Object

Required fields:

 - `observations` - list of Observation objects
 - `id` - unique ID for Action
 - `url` - the URL for the Action. Translated in to the `subj_id` field in the Event.

Optional:

 - `extra-events` - a list of extra Events. Sent *only* if the Action matches. These need only have the following fields. Other fields will be automatically added.
   - subj_id
   - obj_id
   - relation_type_id
   - occurred_at

Optional Fields:

 - `metadata` - the bibliographic metadata for the subject. Translated as the `subj` field in the Event.
 
### Observation Object

Required fields:

 - `type` - a recognised observation type

Type is one of the following:

 - `plaintext` - plain text for the extraction of unlinked DOIs, linked DOIs, article URLs
 - `html` - HTML document or fragment for the extraction of unlinked DOIs, linked DOIs, article URLs
 - `url` - a URL that could be Article Landing Page URLs or unlinked DOIs, or linked DOIs
 - `content-url` - a list of URLs that could point to HTML documents, to be treated as per `html-content`

Other fields depending on type:

 - `plaintext`
     - `input-content`
 - `html-content`
     - `input-url`
 - `url`
     - `input-url`
 - `content-url`
    - `input-url`

## Output Observation fields

Each Observation is transformed, retaining its input (in some form) and providing outputs. Available outputs:

 - `input` - same as the input content
 - `retrieved-content` - input that was retrieved
 - `input-hash` - SHA1 hash of the content input
 - `candidate-unlinked-dois` - candidate DOIs (expressed various ways) extracted by regex, e.g. `10.5555/1234678`, `https://doi.org/10.5555/12345678`
 - `candidate-linked-dois` - candidate HTTP DOIs, extracted by parsing HTML, e.g. `https://doi.org/10.5555/12345678`
 - `candidate-unlinked-landing-pages` - candidate landing pages expressed as URLs in text, extracted by regex and domain list, e.g. `http://psychoceramics.labs.crossref.org/10.5555-12345678.html`
 - `candidate-linked-landing-pages` - candidate landing pages expressed as `<a hrefs>` in HTML, extracted by HTML parsing and domain list, e.g. `http://psychoceramics.labs.crossref.org/10.5555-12345678.html`
 - `matched-unlinked-dois` - mapping of `candidate-unlinked-dois` to normalized, extant DOIs, where it was possible to match
 - `matched-linked-dois` - mapping of `candidate-linked-dois` to normalized, extant, DOIs, where it was possible to match
 - `matched-unlinked-landing-pages` - mapping of `candidate-unlinked-landing-pages` to normalized, extant, DOIs, where it was possible to match
 - `matched-linked-landing-pages` - mapping of `candidate-linked-landing-pages` to normalized, extant, DOIs, where it was possible to match
 - `matched-dois` - list of DOIs, the union of all `matched-*` fields.

 - `plaintext`
     - `input-content`
     - `candidate-unlinked-dois`
     - `candidate-unlinked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-dois`
 - `html`
     - `input-content`
     - `candidate-unlinked-dois`
     - `candidate-linked-dois`
     - `candidate-unlinked-landing-pages`
     - `candidate-linked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-linked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-linked-landing-pages`
     - `matched-dois`
 - `url`
     - `input-url`
     - `candidate-unlinked-doi`
     - `candidate-unlinked-landing-page`
     - `matched-unlinked-landing-page`
     - `matched-doi`
 - `content-url`
     - `input-url` - the URL
     - `retrieved-input`
     - `candidate-unlinked-dois`
     - `candidate-linked-dois`
     - `candidate-unlinked-landing-pages`
     - `candidate-linked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-linked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-linked-landing-pages`
     - `matched-dois`

## Nomenclature

 - 'candidate DOI' - something that looks like a DOI.
 - 'candidate landing page url` - a URL that has the domain name of a landing page, so might be on
 - 'matching' - take a candidate, try to extract a DOI and/or verify that the DOI exists

## Tests

### Unit tests

 - `time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit`

### Component tests

These get Redis involved.

 - `time docker-compose -f docker-compose-component-tests.yml run -w /usr/src/app test lein test :component`



## Running

There are three processes that should be run. They can be scaled independently. 

 - `lein run accept`  - Accept inputs from Agents and enqueue. Lightweight. Recommended 3x for failover.
 - `lein run process` - Process inputs from queue. Recommended > 3x for failover and load balancing.
 - `lein run push` - Push downstream to Event Bus. Lightweight. Recommended 3x for failover.

## Docker

### Production

This should be run with Docker Swarm for load-balancing, service discovery and fail-over. Details can be found in the Event Data System repository.

 - command: `lein run`
 - directory: `/usr/src/app`

## Config

Config via environment variables

 - `S3_KEY`
 - `S3_SECRET`
 - `S3_REGION_NAME`
 - `S3_ACTION_BUCKET_NAME`
 - `PORT`
 - `JWT_SECRETS`
 - `ARTIFACT_BASE` - URL base of bucket where artifact registry is hosted without slash, e.g. `http://event-data-artifact-prod.s3.amazonaws.com`
 - `DUPLICATE_STORAGE` - one of `memory` for testing or `s3` for production
 - `DUPLICATE_BUCKET_NAME` - when s3, name of bucket where duplicate information is stored
 - `DUPLICATE_BUCKET_REGION`
 - `EVIDENCE_STORAGE` - one of `memory` for testing or `s3` for production
 - `EVIDENCE_BUCKET_NAME` - when s3, name of bucket where evidence records should be stored
 - `EVIDENCE_BUCKET_REGION`
 - `EVIDENCE_URL_BASE` - URL base where evidence registry is hosted, e.g. `https://evidence.eventdata.crossref.org`
 - `EVENT_BUS_URL_BASE` - URL base where the event bus is hosted. e.g. `https://bus.eventdata.crossref.org`
 - `DOI_CACHE_REDIS_PORT` - Redis connection to store DOI cache. You may want to segregate this
 - `DOI_CACHE_REDIS_HOST`
 - `DOI_CACHE_REDIS_DB`
 - `LANDING_PAGE_CACHE_REDIS_PORT` - Redis connection to store Landing page -> DOI cache. You may want to segregate this
 - `LANDING_PAGE_CACHE_REDIS_HOST`
 - `LANDING_PAGE_CACHE_REDIS_DB`
 - `ROBOTS_CACHE_REDIS_PORT` - Redis connection to store robots.txt cache. You may want to segregate this
 - `ROBOTS_CACHE_REDIS_HOST`
 - `ROBOTS_CACHE_REDIS_DB`
 - `PROCESS_CONCURRENCY` - number of threads when processing. Default is 10.
 - `SKIP_LANDING_PAGE_CACHE`
## Demo

### Diagnostic in-and-out

Background:

     time docker-compose  -f docker-compose-demo.yml run -w /usr/src/app  -p 8005:8005 accept lein run accept

Then:

     curl http://localhost:8006/input/diagnostic --verbose --data @demo/demo.json --header "Content-type: application/json" -H "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyIxIjoiMSIsInN1YiI6Indpa2lwZWRpYSJ9.w7zV2vtKNzrNDfgr9dfRpv6XYnspILRli_V5vd1J29Q" | jq .

### Full pipeline

Set up all three services (accept, process, push) in addition to a status service, an event bus and a live demo.

     docker-compose -f docker-compose-demo.yml up accept

Tail logs:

     docker-compose -f docker-compose-demo.yml logs -f


Then:

     curl http://localhost:8005/input --verbose --data @demo/demo.json --header "Content-type: application/json" -H "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyIxIjoiMSIsInN1YiI6Indpa2lwZWRpYSJ9.w7zV2vtKNzrNDfgr9dfRpv6XYnspILRli_V5vd1J29Q" | jq .


### Quality

Run code quality check:

    time docker-compose run -w /usr/src/app test lein eastwood

### Coverage

Code coverage from running all tests. Results are found in `target/coverage`.

    lein cloverage

## License

Copyright © 2017 Crossref

Distributed under the The MIT License (MIT).
