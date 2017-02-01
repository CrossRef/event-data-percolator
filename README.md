# Event Data Percolator

The Event Data Percolator identifies and extracts Events from inputs fed to it by Agents. It takes Input Bundles from Agents and creates Evidence Records and Events, and sends them to the Evidence Registry and Event Bus respectively. 

The Event Data Percolator accepts Input Bundles from Agents. An Input Bundle contains URLs, text, HTML and links found by an Agent, along with all the other information necessary to create Events and Evidence Records. The Agent therefore focuses on doing a specific job: connecting to its data source, gathering inputs and sending them to the Percolator in a recognised format.

![](doc/workflow.png)

The Percolator therefore does most of the work involved in finding Events. By having a decoupled service, the service and algorithms that extract data can be improved, upgraded and scaled independently of the Agents.

(Of course, implementors of Event Data Agents are free to use whatever methods are best. The Event Data Percolator implements common patterns observed when building Crossref's Event Data Agents.)

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

An Action must have an ID. This is different to the finished Event ID. The Percolator will only process a trigger once, and if it is subsequently asked to process it, it will politely decline, including a "duplicate" field which includes the date and Evidence Record ID that the Event previously occurred on.

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

### 4. Process each Observation

 - Transform Input Bundle, keeping structure identical.
 - According to the input type, apply the relevant transformation.
 - Each transformation is supplied the value of the "duplicate" value.
 - If it is "false", the transformation is applied normally.
 - If it is not false, the transformation won't attempt to extract any DOIs, but will pass through the input (or a hash of it).

### 5. Create Events and Evidence Record

 - Generate an Evidence Record ID (a UUID).
 - Create an Evidence Record that includes the resulting Input Package under the "input" key
 - For every Action, take the union of DOIs found (as some may be found by more than one Observation).
 - Create a mapping of Action ID to list of Events, include in the Evidence Record under the "events" key.

### 6. Send

 - Send Evidence Record to Evidence Registry with its ID.
 - Send each Event to the Event Bus.
 - Register each Action ID to prevent future duplicates.
 
 ## Details of Input Package format
 
 ### Input Package
 
 Required fields:

 - `source-name` - the name of the source, e.g. `wikipedia`.
 - `source-token` - the unique ID of the agent collecting data.
 - `pages` - list of page objects
 
Other fields may be supplied by the Agent if required, and will be carried through.
 
## Bundle Format

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

Optional Fields:

 - `metadata` - the bibliographic metadata for the subject. Translated as the `subj` field in the Event.
 
### Observation Object

Required fields:

 - `type` - a recognised observation type

Type is one of the following:

 - `plain-text-content` - plain text for the extraction of unlinked DOIs, linked DOIs, article URLs
 - `plain-text-content-sensitive` - as `plain-text-content`, but the content of the text is sensitive so will not be included in the output evidence record
 - `html-content` - HTML document or fragment for the extraction of unlinked DOIs, linked DOIs, article URLs
 - `html-content-sensitive` - as `html-content`, but the content of the text is sensitive so it will not be included in the output evidence record
 - `url` - a URL that could be Article Landing Page URLs or unlinked DOIs, or linked DOIs
 - `html-content-url` - a list of URLs that could point to HTML documents, to be treated as per `html-content`
 - `html-content-url-version` -  a triple of [«canonical-url», «old-version-url», «new-version-url»] that represent a version diff (treated as `html-content-url`s). The result is events that represent the adding and removal of URLs.

Other fields depending on type:

 - `plain-text-content`
     - `input`
 - `plain-text-content-sensitive`
    - `input`
 - `html-content`
     - `input`
 - `html-content-sensitive`
     - `input`
 - `url`
     - `input`
 - `html-content-url`
    - `canonical-url`
    - `old-url`
    - `new-url`
 - `html-content-url-version`
     - `input` - input URL triple

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

 - `plain-text-content`
     - `input`
     - `candidate-unlinked-dois`
     - `candidate-unlinked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-dois`
 - `plain-text-content-sensitive`
     - `input-hash`
     - `candidate-unlinked-dois`
     - `candidate-unlinked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-dois`
 - `html-content`
     - `input`
     - `candidate-unlinked-dois`
     - `candidate-linked-dois`
     - `candidate-unlinked-landing-pages`
     - `candidate-linked-landing-pages`
     - `matched-unlinked-dois`
     - `matched-linked-dois`
     - `matched-unlinked-landing-pages`
     - `matched-linked-landing-pages`
     - `matched-dois`
 - `html-content-sensitive`
     - `input-hash`
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
     - `input`
     - `candidate-unlinked-doi`
     - `candidate-unlinked-landing-page`
     - `matched-unlinked-landing-page`
     - `matched-doi`
 - `html-content-url`
     - `input` - the URL
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
 - `html-content-url-version`
     - `input` - input URL triple

## Tests

### Unit tests

 - `time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit`

## Docker

### Production

This should be run with Docker Swarm for load-balancing, service discovery and fail-over. Details can be found in the Event Data System repository.

 - command: `lein run`
 - directory: `/usr/src/app`

## License

Copyright © 2017 Crossref

Distributed under the The MIT License (MIT).
