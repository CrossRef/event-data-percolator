# Event Data Percolator

<img src="doc/percolator-render.png" align="right" style="float: right">

The Event Data Percolator accepts inputs from Agents in the form of 'skeleton' input Evidence Records. It does all the work that's common to Crossref Agents, including identifying, extracting and validating Events. It emits Evidence Records and Events. This allows the design of an Agent to focus only on the specific job of connecting to an external data source and building input Evidence Records.

An input Evidence Record contains URLs, text, HTML and links found by an Agent, along with all the other information necessary to create Events and finished Evidence Records. 

![](doc/workflow.png)

The Percolator therefore does most of the work involved in finding Events. By having a decoupled service, the service and algorithms that extract data can be improved, upgraded and scaled independently of the Agents.

(Of course, implementors of non-Crossref Event Data Agents are free to use whatever methods are best. The Event Data Percolator implements common patterns observed when building Crossref's Event Data Agents.)

## Input Evidence Record format

An Agent identifies Actions. An Action represents a single actionable stimulus that led to an Event. Examples:

 - a tweet was published
 - a comment was published on a social media site
 - a blog post was published

There are a number of ways that observations can be made: a source may send chunks of HTML, or lists of URLs, or plain text. There are therefore corresponding observation types. Furthermore, some Actions may be observed more than one way: the Gnip URL sends both the text of the tweet and a list of URLs, and a Newsfeed Action can include both the Blog Post's URL and an HTML summary snippet. Each Action therefore has zero or more Observations.

Actions are packaged up in a structure that describes the structure by they were recieved. Some Triggers provide a single Action (e.g. a tweet), some present a list of Actions (e.g. a Newsfeed retrieval) and some present a list of pages of actions (e.g. a Reddit API response). A list of pages, each containing a list of Actions is the common denomenator. For simplicity, all Input Bundles must have this format.

An Input Evidence Record is a JSON object that packages up Actions and Observations, along with any extra required information.

Each Observation type is processed (every type has a different method), and DOIs identified, inferred or extracted. The Percolator takes an Input Evidence Record and, preserving its structure, processes each Observation into an Observation Output. The finished Evidence Record is identical to the Input Evidence Record, but with all Observations transformed.

An Input Evidence Record may also have a `trigger`, which describes the reason that the Evidence Record was created. If the reason was an artifact-scan, the version of the artifact in use is also included. The Trigger information is purely for informational purposes; the content is not inspected by the Percolator.

## Example Input Evidence Records

### Twitter Input Evidence Record

A Twitter trigger is a single tweet. Therefore there's only one page with one Action, but that Action can contain both a `plain-text` observation (for the Tweet text) and potentially a number of `url`observations (as extracted and sent by the Gnip API).

![](doc/input-bundle-formats-twitter.png)

For practicality, the Twitter Agent batches up a number of Actions in each Evidence Record Input.

### Newsfeed Input Evidence Record

A Newsfeed trigger is the retrieval of a given RSS feed. A newsfeed usually contains one page of data, but each page has a number of published blog posts, each of which is an Action. The entry in an RSS feed can have two observations: the URL of the post and a summary HTML snippet.

![](doc/input-bundle-formats-newsfeed.png)

### Reddit Input Evidence Record

A Reddit trigger is a search query for a domain. The API will return a list of pages of posts. Each post has plain-text content.

![](doc/input-bundle-formats-reddit.png)

## Deduplication

An Action should have an ID. This is different to the finished Event ID. The Percolator will only process a trigger once, and if it is subsequently asked to process it, it will politely decline, including a "duplicate" field which includes the date and Evidence Record ID that the Event previously occurred on. Duplicates *within* an Evidence bundle are not detected. Don't send them.

Although recommended, Action IDs aren't compulsory. If you don't send an Action ID then it won't be de-duplicated. There are cases where you might like to do this, e.g. the Wikipedia Agent which sends a very high volume of data with a low signal-to-noise ratio and a very low likelihood of duplicate Events being sent. 

The interpretation and formulation of the ID is up to the Agent:

 - the Twitter agent uses a hash of the tweet ID, ensuring each tweet can only be processed once. This is useful because it allows the Tweet stream to be re-processed with catch-up, with no duplicates introduced.
 - the Newsfeed agent uses a hash of the concatenation of the blog post URL, meaning the blog post can only be processed once, regardless of which feed it was seen on. This is useful because blog feeds repeatedly return previously seen data.
 - the Reddit agent uses a hash of the post ID. This is useful because the Reddit API can return posts previously seen.

## Workflow

The Percolator accepts Input Evidence Records from an Apache Kafka topic. Because of the design of Kafka, clients should be able to deal with duplicate inputs. The Percolator process function doesn't re-process Input Evidence Records when it knows it is already processing it elsewhere, or where it has already been done. It does this by taking out a mutex in Redis (which times out) and by checking the existence of the Evidence Record in the Evidence Registry. Each instance of the Percolator runs a single Kafka client, but 

During a process cycle, the Percolator:

 - accepts a batch of Evidence Records from the Topic
 - does a de-duplication check based on the timeout lock
 - does a de-duplication check based on the Evidence Registry
 - takes out a timeout lock based on the ID (which has been assigned by the Agent as a part of the Evidence Record)
 - dedupe Actions based on previously stored Action IDs
 - process Observations to Candidates
 - match Candidates to DOIs
 - create Events
 - save the Evidence Record to the Evidence Registry
 - set Action IDs for subsequent de-duplication
 - send Events to a downstream Kafka topic
 - finish processing the batch

More detail on each step:

### Dedupe Actions

 - Transform Evidence Record, keeping structure identical.
 - Look up each Action ID.
 - If it has already been seen, set the "duplicate" field to the Event ID and date. 
 - If the Action ID has not already been seen, set the "duplicate" value to "false".

### Process each Observation to extract Candidates

 - Transform Evidence Record, keeping structure identical.
 - According to the input type, apply the relevant transformation to generate candidates.
 - Each transformation is supplied the value of the "duplicate" value.
 - If it is "false", the transformation is applied normally.
 - If it is not false, the transformation won't attempt to extract any DOIs, but will pass through the input (or a hash of it).

### Match Candidates into DOIs

 - Transform Input Bundle, keeping structure identical
 - For every Action:
     - Collect all of the candidate DOIs and Landing Page URLs
     - Attempt to convert each one into a DOI 
 - Deduplicate matches that identify the same DOI from the same input but via different candidates. E.g. a hyperlinked DOI with the DOI also in the link text.

### Create Events and Evidence Record

 - Generate an Evidence Record ID (a UUID).
 - Create an Evidence Record that includes the resulting Input Package under the "input" key
 - For every Action, take the union of DOIs found (as some may be found by more than one Observation).
 - Create a mapping of Action ID to list of Events, include in the Evidence Record under the "events" key.


## Details of Evidence Record format
  
Required fields should be supplied by Agents:

 - `source-name` - the name of the source, e.g. `wikipedia`.
 - `source-token` - the unique ID of the agent collecting data.
 - `pages` - list of page objects
 - `jwt` - this is included with each Event sent to the Events topic (which will be picked by the Event Bus). It is secret, and removed from the public Evidence Record
 - `id` - a unique ID, generated by the Agent. Should be of the form `«YYYYMMDD»-«agent-name«-«uuid»`.
 - `timestamp` - ISO8601 timestamp of when the Evidence Record was produced

Optional fields:
 - `license` - a license URL, attached to each Event
 
Other fields may be supplied by the Agent if required, and will be carried through.
 
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

    time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit

### Component tests

These get Redis involved.

    time docker-compose -f docker-compose-component-tests.yml run -w /usr/src/app test lein test :component

## Running

There is one process to run. It spins up a configurable number of threads. Other copies can be run for failover or parallelism.

 - `lein run process` - Process inputs from queue. Recommended > 3x for failover and load balancing.

## Docker

### Production

This should be run with Docker Swarm for load-balancing, service discovery and fail-over. Details can be found in the Event Data System repository.

 - command: `lein run process`
 - directory: `/usr/src/app`

## Config

The Percolator uses Event Data's global namespace of configuration values. The following environment variables are used:

 - `GLOBAL_ARTIFACT_URL_BASE`
 - `GLOBAL_EVENT_INPUT_TOPIC`
 - `GLOBAL_EVIDENCE_URL_BASE`
 - `GLOBAL_KAFKA_BOOTSTRAP_SERVERS`
 - `GLOBAL_STATUS_TOPIC`
 - `PERCOLATOR_DOI_CACHE_REDIS_DB`
 - `PERCOLATOR_DOI_CACHE_REDIS_HOST`
 - `PERCOLATOR_DOI_CACHE_REDIS_PORT`
 - `PERCOLATOR_DUPLICATE_BUCKET_NAME`
 - `PERCOLATOR_DUPLICATE_REGION_NAME`
 - `PERCOLATOR_DUPLICATE_STORAGE` - one of `memory` for testing or `s3` for production
 - `PERCOLATOR_EVIDENCE_BUCKET_NAME`
 - `PERCOLATOR_EVIDENCE_REGION_NAME`
 - `PERCOLATOR_EVIDENCE_STORAGE` one of `memory` for testing or `s3` for production
 - `PERCOLATOR_INPUT_EVIDENCE_RECORD_TOPIC`
 - `PERCOLATOR_LANDING_PAGE_CACHE_REDIS_HOST`
 - `PERCOLATOR_LANDING_PAGE_CACHE_REDIS_PORT`
 - `PERCOLATOR_MUTEX_REDIS_DB`
 - `PERCOLATOR_MUTEX_REDIS_HOST`
 - `PERCOLATOR_MUTEX_REDIS_PORT`
 - `PERCOLATOR_MUTEX_REDIS_PORT` 
 - `PERCOLATOR_ROBOTS_CACHE_REDIS_DB`
 - `PERCOLATOR_ROBOTS_CACHE_REDIS_HOST`
 - `PERCOLATOR_ROBOTS_CACHE_REDIS_PORT`
 - `PERCOLATOR_PROCESS_CONCURRENCY`
 - `PERCOLATOR_S3_KEY`
 - `PERCOLATOR_S3_SECRET`
 - `PERCOLATOR_SKIP_DOI_CACHE` - true or don't set
 - `PERCOLATOR_SKIP_LANDING_PAGE_CACHE` - true or don't set
 - `PERCOLATOR_SKIP_ROBOTS_CACHE` - true or don't set
 - `PERCOLATOR_LOG_LEVEL` - one of 'debug' or 'info'. Defaults to 'info'.
 - `PERCOLATOR_KAFKA_CONSUMER_GROUP_BUMP` - leave empty by default. If supplied, can be used to bump to a new consumer group during sysadmin
 
## Configure Kafka

You should create a Kafka topic for the Input Evidence records with suffucient partitions for future expansion. Also note that a status topic should exist. Topic name should agree with value of PERCOLATOR_INPUT_EVIDENCE_RECORD_TOPIC and GLOBAL_EVENT_INPUT_TOPIC

    bin/kafka-topics.sh --create --partitions 200 --topic percolator-input-evidence-record --zookeeper localhost --replication-factor 2

    bin/kafka-topics.sh --create --partitions 2 --topic event-input --zookeeper localhost --replication-factor 2

If the input queue is rebalanced quickly (due to extra instances spinning up or down) then some Events may be caught in the 1 minute mutex timeout, meaning that it never gets processed. For this reason the `cleanup` mode should be run on a periodic basis (e.g. every week).

## Demo

### Quality

Run code quality check:

    time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein eastwood

### Coverage

Code coverage from running all tests. Results are found in `target/coverage`.

    lein cloverage

## License

Copyright © 2017 Crossref

Distributed under the The MIT License (MIT).
