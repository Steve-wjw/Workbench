package com.aiworkbench.tag;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TagControllerTest {
    private final CapturingRepository repository = new CapturingRepository();
    private final MockMvc mvc = standaloneSetup(new TagController(new TagService(repository))).build();

    @Test
    void createsTagWithoutManualSlug() throws Exception {
        mvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RAG\",\"color\":\"#0f766e\"}"))
                .andExpect(status().isCreated());

        assertEquals("RAG", repository.name);
        assertEquals("#0f766e", repository.color);
    }

    @Test
    void editsTagWithoutManualSlug() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(patch("/api/v1/tags/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated RAG\",\"color\":\"#123456\"}"))
                .andExpect(status().isOk());

        assertEquals(id, repository.id);
        assertEquals("Updated RAG", repository.name);
        assertEquals("#123456", repository.color);
    }

    private static class CapturingRepository extends TagRepository {
        private UUID id;
        private String name;
        private String color;

        CapturingRepository() {
            super(null);
        }

        @Override
        public TagModels.TagResponse findById(UUID id) {
            return new TagModels.TagResponse(id, name == null ? "RAG" : name, null, color, 0, null, null);
        }

        @Override
        public TagModels.TagResponse insert(TagModels.CreateTagRequest request) {
            name = request.name();
            color = request.color();
            id = UUID.randomUUID();
            return findById(id);
        }

        @Override
        public TagModels.TagResponse update(UUID tagId, TagModels.UpdateTagRequest request) {
            id = tagId;
            name = request.name();
            color = request.color();
            return findById(id);
        }
    }
}
