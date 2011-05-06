package com.tinkerpop.rexster;

import com.tinkerpop.blueprints.pgm.Graph;
import com.tinkerpop.blueprints.pgm.impls.readonly.ReadOnlyGraph;

import com.tinkerpop.rexster.extension.*;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response.Status;

@Path("/{graphname}")
public class GraphResource extends AbstractSubResource {

    private static Logger logger = Logger.getLogger(GraphResource.class);

    public GraphResource() {
        super(null);
    }

    public GraphResource(UriInfo ui, HttpServletRequest req, RexsterApplicationProvider rap) {
        super(rap);
        this.httpServletRequest = req;
        this.uriInfo = ui;
    }

    /**
     * GET http://host/graph
     * graph.toString();
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getGraph(@PathParam("graphname") String graphName) {
        try {

            // graph should be ready to go at this point.  checks in the
            // constructor ensure that the rag is not null.
            Graph graph = this.getRexsterApplicationGraph(graphName).getGraph();

            this.resultObject.put("name", graphName);
            this.resultObject.put("graph", graph.toString());
            
            boolean isReadOnly = false;
            String graphType = graph.getClass().getName();
            if (graph instanceof ReadOnlyGraph) {
            	// readonly graphs must unwrap to the underlying graph implementation
            	graphType = ((ReadOnlyGraph) graph).getRawGraph().getClass().getName();
            	isReadOnly = true;
            }
            
            this.resultObject.put(Tokens.READ_ONLY, isReadOnly);
            this.resultObject.put("type", graphType);
            this.resultObject.put(Tokens.QUERY_TIME, this.sh.stopWatch());
            this.resultObject.put(Tokens.UP_TIME, this.getTimeAlive());
            this.resultObject.put("version", RexsterApplication.getVersion());

            JSONArray extensionsList = getExtensionHypermedia(graphName, ExtensionPoint.GRAPH);
            if (extensionsList != null) {
                this.resultObject.put(Tokens.EXTENSIONS, extensionsList);
            }

        } catch (JSONException ex) {
            logger.error(ex);
            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();
    }

    @POST
    @Path("{extension: (?!vertices)(?!edges)(?!indices)(?!prefixes).+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response getGraphExtension(@PathParam("graphname") String graphName, JSONObject json) {
        this.setRequestObject(json);
        return this.getGraphExtension(graphName);
    }

    @GET
    @Path("{extension: (?!vertices)(?!edges)(?!indices)(?!prefixes).+}")
    public Response getGraphExtension(@PathParam("graphname") String graphName) {

        ExtensionResponse extResponse;
        ExtensionMethod methodToCall;
        ExtensionSegmentSet extensionSegmentSet = parseUriForExtensionSegment(graphName, ExtensionPoint.GRAPH);

        // determine if the namespace and extension are enabled for this graph
        RexsterApplicationGraph rag = this.getRexsterApplicationGraph(graphName);

        if (rag.isExtensionAllowed(extensionSegmentSet)) {

            Object returnValue = null;

            // namespace was allowed so try to run the extension
            try {

                // look for the extension as loaded through serviceloader
                RexsterExtension rexsterExtension = findExtension(extensionSegmentSet);

                if (rexsterExtension == null) {
                    // extension was not found for some reason
                    logger.error("The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "].  Check com.tinkerpop.rexster.extension.RexsterExtension file in META-INF.services.");
                    JSONObject error = generateErrorObject(
                            "The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "]");
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
                }

                // look up the method on the extension that needs to be called.
                methodToCall = findExtensionMethod(rexsterExtension, ExtensionPoint.GRAPH, extensionSegmentSet.getExtensionMethod());

                if (methodToCall == null) {
                    // extension method was not found for some reason
                    logger.error("The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "].  Check com.tinkerpop.rexster.extension.RexsterExtension file in META-INF.services.");
                    JSONObject error = generateErrorObject(
                            "The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "]");
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
                }

                // found the method...time to do work
                returnValue = invokeExtension(graphName, rexsterExtension, methodToCall);

            } catch (WebApplicationException wae){
                // already logged this...just throw it  up.
                throw wae;
            } catch (Exception ex) {
                logger.error("Dynamic invocation of the [" + extensionSegmentSet + "] extension failed.", ex);
                JSONObject error = generateErrorObjectJsonFail(ex);
                throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
            }

            if (returnValue instanceof ExtensionResponse) {
                extResponse = (ExtensionResponse) returnValue;

                if (extResponse.isErrorResponse()) {
                    // an error was raised within the extension.  pass it back out as an error.
                    logger.warn("The [" + extensionSegmentSet + "] extension raised an error response.");
                    throw new WebApplicationException(this.addHeaders(Response.fromResponse(extResponse.getJerseyResponse())).build());
                }
            } else {
                // extension method is not returning the correct type...needs to be an ExtensionResponse
                logger.error("The [" + extensionSegmentSet + "] extension does not return an ExtensionResponse.");
                JSONObject error = generateErrorObject(
                        "The [" + extensionSegmentSet + "] extension does not return an ExtensionResponse.");
                throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
            }

        } else {
            // namespace was not allowed
            logger.error("The [" + extensionSegmentSet + "] extension was not configured for [" + graphName + "]");
            JSONObject error = generateErrorObject(
                    "The [" + extensionSegmentSet + "] extension was not configured for [" + graphName + "]");
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        String mediaType = MediaType.APPLICATION_JSON;
        if (methodToCall != null) {
            mediaType = methodToCall.getExtensionDefinition().produces();
            extResponse = tryAppendRexsterAttributesIfJson(extResponse, methodToCall, mediaType);
        }

        return this.addHeaders(Response.fromResponse(extResponse.getJerseyResponse()).type(mediaType)).build();
    }

    /**
     * DELETE http://host/graph
     * graph.clear()
     *
     * @return Query time
     */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteGraph(@PathParam("graphname") String graphName) {
        Graph graph = this.getRexsterApplicationGraph(graphName).getGraph();
        graph.clear();

        try {
            this.resultObject.put(Tokens.QUERY_TIME, sh.stopWatch());
        } catch (JSONException ex) {
            logger.error(ex);
            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();

    }
}
