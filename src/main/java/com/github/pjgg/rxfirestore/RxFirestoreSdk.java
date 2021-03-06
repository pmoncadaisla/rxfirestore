/*
 * Copyright 2019 RxFirestore.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.pjgg.rxfirestore;

import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_CLOSE;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_DELETE;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_EMPTY;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_GET;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_INSERT;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_QUERY;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_QUERY_BUILDER;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_UPDATE;
import static com.github.pjgg.rxfirestore.FirestoreTemplate.TOPIC_UPSERT;

import com.github.pjgg.rxfirestore.exceptions.NotFoundExceptions;
import io.reactivex.Observable;
import io.reactivex.subjects.SingleSubject;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.reactivex.core.eventbus.EventBus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.apache.commons.lang3.SerializationUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.firestore.EventListener;
import com.google.cloud.firestore.QuerySnapshot;

import io.reactivex.Single;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.Json;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.eventbus.Message;

/**
 * RxFirestoreSdk is a data access object implementation for Google Firestore database. In order to use it, your
 * repositories must extends this class, where E means the entity type that you want to manage in your collection
 * <p>
 * This implementation will give you commons methods in order to work with firestore, but you could overwrite them or
 * implements your own methods in your repository.
 *
 * NOTE: you must set GCLOUD_KEY_PATH environment variable pointing to you keyfile.json. Additionally you could set
 * DB_THREAD_POOL_SIZE environment variable in order to set the amount of thread that you would like to have in order to
 * manage all firestore connections. By default, DB_THREAD_POOL_SIZE will be set to the number of cores that you have X
 * 2.
 */
public class RxFirestoreSdk<E extends Entity> {

	private static final Logger LOG = LoggerFactory.getLogger(RxFirestoreSdk.class);

	private static final long SEND_TIMEOUT_MS = 59000;
	private final Supplier<? extends Entity> supplier;
	private final BlockingFirestoreTemplate blockingFirestoreTemplate;

	public RxFirestoreSdk(Supplier<? extends Entity> entityConstructor) {
		supplier = Objects.requireNonNull(entityConstructor);
		FirestoreTemplateFactory.INSTANCE.init();
		blockingFirestoreTemplate = new BlockingFirestoreTemplate(
			supplier,
			FirestoreTemplateFactory.INSTANCE.getVertx()
		);
	}

	public RxFirestoreSdk(Supplier<? extends Entity> entityConstructor, Vertx vertx) {
		supplier = Objects.requireNonNull(entityConstructor);
		FirestoreTemplateFactory.INSTANCE.init(vertx);
		SingleSubject<Vertx> vertxSubject = SingleSubject.create();
		vertxSubject.onSuccess(vertx);
		blockingFirestoreTemplate = new BlockingFirestoreTemplate(supplier, vertxSubject);
	}

	/**
	 * Insert create a Document with an auto-generate ID. Firestore auto-generated IDs do not provide any automatic
	 * ordering. If you want to be able to order your documents by creation date, you should store a timestamp as a
	 * field in the documents.
	 *
	 * @return Single document key ID.
	 */
	public Single<String> insert(final E entity) {

		LOG.trace("insert called. Collection name "
			+ entity.getCollectionName()
			+ " Entity "
			+ entity.toJson());

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", entity.getCollectionName());

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_INSERT, entity.toJson(), deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Msg " + message);
				return message;
			});
	}

	/**
	 * Empty create a document for a given collection, and return an an auto-generate ID. In some cases, it can be
	 * useful to create a document reference with an auto-generated ID, then use the reference later through a upsert
	 * method.
	 *
	 * @param collectionName against which you want to make the query.
	 * @return Single document key ID.
	 */
	public Single<String> empty(final String collectionName) {
		LOG.trace("empty called. Collection name " + collectionName);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_EMPTY, "", deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Msg " + message);
				return message;
			});
	}

	/**
	 * queryBuilder allow you to develop your own query with where statement. Use in combination with get in order to
	 * develop complex inferences.
	 *
	 * @param collectionName against which you want to make the query.
	 * @return Query
	 * <p>
	 * example:
	 * <p>
	 * var query = carsRepository.queryBuilder(CarModel.CARS_COLLECTION_NAME).whereEqualTo("brand","Toyota");
	 */
	public Single<Query> queryBuilder(final String collectionName) {
		LOG.trace("queryBuilder called. Collection name " + collectionName);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<byte[]>rxSend(TOPIC_QUERY_BUILDER, "", deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Query created ");
				return SerializationUtils.deserialize(message);
			});
	}

	public Query queryBuilderSync(final String collectionName) {

		LOG.trace("queryBuilderSync called. Collection name " + collectionName);

		Query queryBuilderResult = blockingFirestoreTemplate.queryBuilder(collectionName);

		LOG.trace("Reply received. Query created ");

		return queryBuilderResult;
	}

	/**
	 * get will retrieve a List of Documents by a given query.
	 *
	 * @param query .Build your query with queryBuilder method.
	 * @return a single list of documents that match query criteria.
	 */
	public Single<List<E>> get(Query query) {
		LOG.trace("get called. Collection name " + query.getCollectionName());

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_QUERY, SerializationUtils.serialize(query), deliveryOpt)
			.map(Message::body)
			.map(message -> {
				List<E> result = new ArrayList<>();
				List<HashMap> data = Json.decodeValue(message, new TypeReference<List<HashMap>>() {
				});
				data.stream().forEach(elem -> result.add((E) supplier.get().fromJsonAsMap(elem)));
				LOG.trace("Reply received. Amount of elements retrieved " + result.size());
				return result;
			});
	}

	/**
	 * get will retrieve a Document by ID for a given collection name.
	 *
	 * @param collectionName against which you want to make the query.
	 * @param id , document ID that you would like to retrieve
	 * @return Single document
	 */
	public Single<E> get(final String id, final String collectionName) {
		LOG.trace("get called. Collection name " + collectionName + " ID " + id);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);
		deliveryOpt.addHeader("_id", id);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_GET, "", deliveryOpt)
			.map(Message::body)
			.map(message -> {
				HashMap data = Json.decodeValue(message, HashMap.class);
				LOG.trace("Reply received.");
				return (E) supplier.get().fromJsonAsMap(data);
			});
	}

	/**
	 * If the document does not exist, it will be created. If the document does exist, its contents will be overwritten
	 * with the newly provided data.
	 * <p>
	 * When you use upsert to create or update a document, you must specify an ID for the document. But sometimes there
	 * isn't a meaningful ID for the document, and it's more convenient to let Cloud Firestore auto-generate an ID for
	 * you. You can do this by calling empty.
	 *
	 * @param collectionName against which you want to upsert.
	 * @return Single boolean.
	 */
	public Single<Boolean> upsert(final String id, final String collectionName, final E entity) {
		LOG.trace("upsert called. Collection name " + collectionName + " ID " + id);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);
		deliveryOpt.addHeader("_id", id);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<Boolean>rxSend(TOPIC_UPSERT, entity.toJson(), deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Element " + id + " updated or created.");
				return message;
			});
	}

	/**
	 * Update full document (overwrite).
	 *
	 * @param collectionName against which you want to make the query.
	 * @return Single boolean. True means updated.
	 */
	public Single<Boolean> update(final String id, final String collectionName, final E entity) {
		LOG.trace("update called. Collection name " + collectionName + " ID " + id);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);
		deliveryOpt.addHeader("_id", id);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_UPDATE, entity.toJson(), deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Element " + id + " updated.");
				return Boolean.valueOf(message);
			});
	}


	/**
	 * To delete a document, use the delete method. Deleting a document does not delete its subcollections!
	 *
	 * @return Single boolean
	 */
	public Single<Boolean> delete(final String id, final String collectionName) {
		LOG.trace("delete called. Collection name " + collectionName + " ID " + id);

		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		deliveryOpt.addHeader("_collectionName", collectionName);
		deliveryOpt.addHeader("_id", id);

		LOG.trace("Sending msg to Eventbus. Delivery option: timeout "
			+ deliveryOpt.getSendTimeout()
			+ " local only "
			+ deliveryOpt.isLocalOnly());

		return eventBus.<String>rxSend(TOPIC_DELETE, "", deliveryOpt)
			.map(Message::body)
			.map(message -> {
				LOG.trace("Reply received. Element " + id + " deleted.");
				return Boolean.valueOf(message);
			});
	}

	/**
	 * addQueryListener, You can listen to a document changes (create, update and delete).
	 *
	 * @param query to subscribe. Build your query with queryBuilder method.
	 * @param eventsHandler will handler document changes. By default we provide an eventHandler that will give you a
	 * Flowable with all the document changes.
	 * @return EventListenerResponse, contains two object. "registration" will allow you to close the event flow and
	 * eventsFlow that will give you an events Flowable
	 * @throws TimeoutException default timeout after 10 seconds
	 * @throws ExecutionException if something weird happens
	 * @throws InterruptedException if something weird happens as somebody break connection.
	 * <p>
	 * example:
	 * <p>
	 * {@code listener.getRegistration().remove();}
	 * <p>
	 * "eventsFlow" represent a flow of changes. Firstly you will get all the events that match with your query,and then
	 * all the changes until you close your listener.
	 * <p>
	 * example:
	 * <p>
	 * {@code listener.getEventsFlow().subscribe(event -> System.out.println("Event Type:"+ event.getEventType() + "
	 * model: " + event.getModel()));}
	 */

	public EventListenerResponse<E> addQueryListener(final Query query,
		final Optional<EventListener<QuerySnapshot>> eventsHandler)
		throws InterruptedException, ExecutionException, TimeoutException {
		return blockingFirestoreTemplate.addQueryListener(query, eventsHandler);
	}

	public void closeConnection() {
		final EventBus eventBus = FirestoreTemplateFactory.INSTANCE.getEventBus();
		final DeliveryOptions deliveryOpt = new DeliveryOptions();
		deliveryOpt.setLocalOnly(true);
		deliveryOpt.setSendTimeout(SEND_TIMEOUT_MS);
		eventBus.publish(TOPIC_CLOSE, null, deliveryOpt);
	}

}

