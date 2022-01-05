"""
Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0
"""
import logging
import json
import time
from flask import Flask, Response, render_template
from stream_manager import (
    ExportDefinition,
    MessageStreamDefinition,
    ReadMessagesOptions,
    ResourceNotFoundException,
    S3ExportTaskDefinition,
    S3ExportTaskExecutorConfig,
    Status,
    StatusConfig,
    StatusLevel,
    StatusMessage,
    StrategyOnFull,
    StreamManagerClient,
    StreamManagerException,
)
from stream_manager.util import Util

app = Flask(__name__)

counter = 0

client = StreamManagerClient()

def fetch():
	response = []
	try:
		stream_description = client.describe_message_stream(stream_name="GGTargetStream")
		message_list = client.read_messages(
			stream_name="GGTargetStream",
			# By default, if no options are specified, it tries to read one message from the beginning of the stream.
			options=ReadMessagesOptions(
				desired_start_sequence_number=stream_description.storage_status.newest_sequence_number-10,
				# Try to read from sequence number 100 or greater. By default, this is 0.
				min_message_count=5,
				# Try to read 10 messages. If 10 messages are not available, then NotEnoughMessagesException is raised. By default, this is 1.
				max_message_count=5,    # Accept up to 100 messages. By default this is 1.
				read_timeout_millis=3000
				# Try to wait at most 5 seconds for the min_messsage_count to be fulfilled. By default, this is 0, which immediately returns the messages or an exception.
			)
		)
		for message in message_list:
			response.append(message.payload.decode())
	except StreamManagerException:
		logging.exception("StreamManagerException")
	except ConnectionError or asyncio.TimeoutError:
		logging.exception("Timed out while executing")
	
	
	return str(response).replace("'","")

@app.route("/")
def index():
	return render_template("index.html")
@app.route("/ts")
def timeseries():
	return render_template("ts.html")

@app.route('/data')
def dump():
	return Response(fetch(), mimetype='application/json')
	
@app.route('/tesim')
def stream():
	with open("../tesim.log", "r") as f:
		content = f.read()
	return app.response_class(content, mimetype='text/plain')
