package com.axway.apim.apiExport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axway.apim.apiImport.APIManagerAdapter;
import com.axway.apim.lib.AppException;
import com.axway.apim.lib.ErrorCode;
import com.axway.apim.swagger.api.properties.APIDefintion;
import com.axway.apim.swagger.api.properties.APIImage;
import com.axway.apim.swagger.api.properties.cacerts.CaCert;
import com.axway.apim.swagger.api.properties.outboundprofiles.OutboundProfile;
import com.axway.apim.swagger.api.state.ActualAPI;
import com.axway.apim.swagger.api.state.DesiredAPI;
import com.axway.apim.swagger.api.state.IAPI;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;

public class APIExportConfigAdapter {
	private static Logger LOG = LoggerFactory.getLogger(APIExportConfigAdapter.class);

	/** Which APIs should be exported identified by the path */
	private String exportApiPath = null;

	/** Where to store the exported API-Definition */
	private String localFolder = null;

	APIManagerAdapter apiManager;

	public APIExportConfigAdapter(String exportApiPath, String localFolder) throws AppException {
		super();
		this.exportApiPath = exportApiPath;
		this.localFolder = localFolder;
		apiManager = APIManagerAdapter.getInstance();
	}

	public void exportAPIs() throws AppException {
		List<ExportAPI> exportAPIs = getAPIsToExport();
		for (ExportAPI exportAPI : exportAPIs) {
			saveAPILocally(exportAPI);
		}
	}

	private List<ExportAPI> getAPIsToExport() throws AppException {
		List<ExportAPI> exportAPIList = new ArrayList<ExportAPI>();
		ExportAPI exportAPI = null;
		if (!this.exportApiPath.contains("*")) {
			JsonNode mgrAPI = apiManager.getExistingAPI(this.exportApiPath);
			IAPI actualAPI = apiManager.getAPIManagerAPI(mgrAPI, getAPITemplate());
			handleCustomProperties(actualAPI);
			exportAPI = new ExportAPI(actualAPI);
			exportAPIList.add(exportAPI);
		} else {
			throw new UnsupportedOperationException("Wildcard API selection not yet supported.");
		}
		return exportAPIList;
	}

	private void saveAPILocally(ExportAPI exportAPI) throws AppException {
		String apiPath = getAPIExportFolder(exportAPI.getPath());
		File localFolder = new File(this.localFolder + apiPath);
		if (!localFolder.mkdirs()) {
			throw new AppException("Cant create export folder", ErrorCode.UNXPECTED_ERROR);
		}
		APIDefintion apiDef = exportAPI.getAPIDefinition();
		String targetFile = null;
		try {
			targetFile = localFolder.getCanonicalPath() + "/" + exportAPI.getAPIDefinition().getAPIDefinitionFile();
			writeBytesToFile(apiDef.getAPIDefinitionContent(), targetFile);
		} catch (IOException e) {
			throw new AppException("Can't save API-Definition locally to file: " + targetFile,
					ErrorCode.UNXPECTED_ERROR, e);
		}
		ObjectMapper mapper = new ObjectMapper();
		FilterProvider filters = new SimpleFilterProvider()
				.addFilter("IgnoreImportFields",
						SimpleBeanPropertyFilter.filterOutAllExcept(new String[] {"inbound", "outbound", "certFile" }))
				.addFilter("IgnoreApplicationFields",
						SimpleBeanPropertyFilter.filterOutAllExcept(new String[] {"name", "oauthClientId", "extClientId", "apiKey" }));
		mapper.setFilterProvider(filters);
		try {
			prepareToSave(exportAPI);
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
			mapper.writeValue(new File(localFolder.getCanonicalPath() + "/api-config.json"), exportAPI);
		} catch (Exception e) {
			throw new AppException("Can't write API-Configuration file.", ErrorCode.UNXPECTED_ERROR, e);
		}
		APIImage image = exportAPI.getAPIImage();
		if(image!=null) {
			writeBytesToFile(image.getImageContent(), localFolder+"/" + image.getBaseFilename()+image.getFileExtension());
		}
		if(exportAPI.getCaCerts()!=null && !exportAPI.getCaCerts().isEmpty()) {
			storeCaCerts(localFolder, exportAPI.getCaCerts());
		}
		LOG.info("Successfully export API to folder: " + localFolder);
	}
	
	private void storeCaCerts(File localFolder, List<CaCert> caCerts) throws AppException {
		for(CaCert caCert : caCerts) {
			String filename = caCert.getCertFile();
			Base64.Encoder encoder = Base64.getMimeEncoder(64, System.getProperty("line.separator").getBytes());
			Base64.Decoder decoder = Base64.getDecoder();
			final String encodedCertText = new String(encoder.encode(decoder.decode(caCert.getCertBlob())));
			byte[] certContent = ("-----BEGIN CERTIFICATE-----\n"+encodedCertText+"\n-----END CERTIFICATE-----").getBytes();
			try {
				writeBytesToFile(certContent, localFolder + "/" + filename);
			} catch (AppException e) {
				throw new AppException("Can't write certificate to disc", ErrorCode.UNXPECTED_ERROR, e);
			}
		}
	}

	private static void writeBytesToFile(byte[] bFile, String fileDest) throws AppException {

		try (FileOutputStream fileOuputStream = new FileOutputStream(fileDest)) {
			fileOuputStream.write(bFile);
		} catch (IOException e) {
			throw new AppException("Can't write file", ErrorCode.UNXPECTED_ERROR, e);
		}
	}

	private String getAPIExportFolder(String apiExposurePath) {
		if (apiExposurePath.startsWith("/"))
			apiExposurePath = apiExposurePath.replaceFirst("/", "");
		if (apiExposurePath.endsWith("/"))
			apiExposurePath = apiExposurePath.substring(0, apiExposurePath.length() - 1);
		apiExposurePath = apiExposurePath.replace("/", "-");
		return apiExposurePath;
	}

	/**
	 * We need this template to enforce loading of all properties for the actual
	 * API, without that API-ManagerAdapter will skip not defined properties.
	 * 
	 * @return
	 * @throws AppException
	 */
	private IAPI getAPITemplate() throws AppException {
		IAPI apiTemplate = new DesiredAPI();
		apiTemplate.setState(IAPI.STATE_PUBLISHED);
		apiTemplate.setClientOrganizations(new ArrayList<String>());
		return apiTemplate;
	}
	
	private void handleCustomProperties(IAPI actualAPI) throws AppException {
		JsonNode customPropconfig = APIManagerAdapter.getCustomPropertiesConfig().get("api");
		Map<String, String> customProperties = new LinkedHashMap<String, String>();
		JsonNode actualApiConfig = ((ActualAPI)actualAPI).getApiConfiguration();
		// Check if Custom-Properties are configured
		Iterator<String> customPropKeys = customPropconfig.fieldNames();
		while(customPropKeys.hasNext()) {
			String key = customPropKeys.next();
			if(actualApiConfig.has(key)) {
				JsonNode value = actualApiConfig.get(key);
				if(value==null) continue;
					customProperties.put(key, value.asText());
			}
		}
		if(customProperties.size()>0) {
			((ActualAPI)actualAPI).setCustomProperties(customProperties);
		}
	}
	
	private void prepareToSave(ExportAPI exportAPI) throws AppException {
		// Clean-Up some internal fields in Outbound-Profiles
		OutboundProfile profile = exportAPI.getOutboundProfiles().get("_default");
		profile.setApiId(null);
		profile.setApiMethodId(null);
	}
}
