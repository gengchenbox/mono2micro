package pt.ist.socialsoftware.mono2micro.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import javax.management.openmbean.KeyAlreadyExistsException;
import javax.servlet.annotation.MultipartConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;
import pt.ist.socialsoftware.mono2micro.domain.Codebase;
import pt.ist.socialsoftware.mono2micro.domain.Dendrogram;
import pt.ist.socialsoftware.mono2micro.domain.Graph;
import pt.ist.socialsoftware.mono2micro.manager.CodebaseManager;

@RestController
@RequestMapping(value = "/mono2micro/codebase/{codebaseName}")
public class DendrogramController {

	private static final Logger logger = LoggerFactory.getLogger(DendrogramController.class);

	private final CodebaseManager codebaseManager = CodebaseManager.getInstance();

	@RequestMapping(value = "/dendrograms", method = RequestMethod.GET)
	public ResponseEntity<List<Dendrogram>> getDendrograms(
		@PathVariable String codebaseName,
		@RequestParam List<String> fieldNames
	) {
		logger.debug("getDendrograms");

		try {
			return new ResponseEntity<>(
				codebaseManager.getCodebaseDendrogramsWithFields(
					codebaseName,
					new HashSet<>(fieldNames)
				),
				HttpStatus.OK
			);

		} catch (IOException e) {
			e.printStackTrace();
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}
	}


	@RequestMapping(value = "/dendrogram/{dendrogramName}", method = RequestMethod.GET)
	public ResponseEntity<Dendrogram> getDendrogram(
		@PathVariable String codebaseName,
		@PathVariable String dendrogramName,
		@RequestParam List<String> fieldNames
	) {
		logger.debug("getDendrogram");

		try {
			// FIXME Instead of parsing the whole list of dendrograms, it would be much better to just parse the right one
			// FIXME or the respective dendrogram directory could also have a dendrogram.json
			return new ResponseEntity<>(
				codebaseManager.getCodebaseDendrogramsWithFields(
					codebaseName,
					new HashSet<>(fieldNames)
				)
					.stream()
					.filter(dendrogram -> dendrogram.getName().equals(dendrogramName))
					.findAny()
					.orElseThrow(() -> new Exception("Dendrogram " + dendrogramName + " not found")),
				HttpStatus.OK
			);

		} catch (Exception e) {
			e.printStackTrace();
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}

	}


	@RequestMapping(value = "/dendrogram/{dendrogramName}/image", method = RequestMethod.GET)
	public ResponseEntity<byte[]> getDendrogramImage(
		@PathVariable String codebaseName,
		@PathVariable String dendrogramName
	) {
		logger.debug("getDendrogramImage");

		try {
			return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.body(codebaseManager.getDendrogramImage(codebaseName, dendrogramName));

		} catch (IOException e) {
			e.printStackTrace();
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}
	}


	@RequestMapping(value = "/dendrogram/{dendrogramName}/delete", method = RequestMethod.DELETE)
	public ResponseEntity<HttpStatus> deleteDendrogram(
		@PathVariable String codebaseName,
		@PathVariable String dendrogramName
	) {
		logger.debug("deleteDendrogram");
		
		try {
			Codebase codebase = codebaseManager.getCodebase(codebaseName);
			codebase.deleteDendrogram(dendrogramName);
			codebaseManager.writeCodebase(codebase);

            return new ResponseEntity<>(HttpStatus.OK);

        } catch (IOException e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
	}


	@RequestMapping(value = "/dendrogram/create", method = RequestMethod.POST)
	public ResponseEntity<HttpStatus> createDendrogram(
		@PathVariable String codebaseName,
		@RequestBody Dendrogram dendrogram
	) {
		logger.debug("createDendrogram");

		try {
			// FIXME The whole codebase needs to be fetched because it needs to be written as a whole again
			// FIXME The best solution would be each "dendrogram directory could also have a dendrogram.json"
			Codebase codebase = codebaseManager.getCodebase(codebaseName);

			if (codebase.isStatic()) {
				codebase.createStaticDendrogram(dendrogram);
			} else {
				codebase.createDynamicDendrogram(dendrogram);
			}

            codebaseManager.writeCodebase(codebase);
            return new ResponseEntity<>(HttpStatus.CREATED);

		} catch (KeyAlreadyExistsException e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);

		} catch (Exception e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
	}


	@RequestMapping(value = "/dendrogram/{dendrogramName}/cut", method = RequestMethod.POST)
	public ResponseEntity<HttpStatus> cutDendrogram(
		@PathVariable String codebaseName,
		@PathVariable String dendrogramName,
		@RequestBody Graph graph
	) {
		logger.debug("cutDendrogram");

		try {
			// FIXME The whole codebase needs to be fetched because it needs to be written as a whole again
			// FIXME The best solution would be each "dendrogram directory could also have a dendrogram.json"
			Codebase codebase = codebaseManager.getCodebase(codebaseName);

            codebase.getDendrogram(dendrogramName).cut(graph);
            codebaseManager.writeCodebase(codebase);
            return new ResponseEntity<>(HttpStatus.OK);

        } catch (Exception e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
	}


	@RequestMapping(value = "/dendrogram/{dendrogramName}/expertCut", method = RequestMethod.POST)
	public ResponseEntity<HttpStatus> createExpertCut(
		@PathVariable String codebaseName,
		@PathVariable String dendrogramName,
		@RequestParam String expertName,
		@RequestParam Optional<MultipartFile> expertFile
	) {
		logger.debug("createExpertCut");

		try {
			// FIXME The whole codebase needs to be fetched because it needs to be written as a whole again
			// FIXME The best solution would be each "dendrogram directory could also have a dendrogram.json"

			Codebase codebase = codebaseManager.getCodebase(codebaseName);
            codebase.getDendrogram(dendrogramName).createExpertCut(expertName, expertFile);
            codebaseManager.writeCodebase(codebase);

            return new ResponseEntity<>(HttpStatus.OK);

        } catch (KeyAlreadyExistsException e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);

        } catch (Exception e) {
			e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
	}
}