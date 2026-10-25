from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PolicyChunk(StrictModel):
    id: str
    text: str = Field(min_length=1, max_length=12000)
    page: int | None = Field(default=None, ge=1)
    section: str | None = Field(default=None, max_length=300)


class PolicyDocument(StrictModel):
    id: str
    title: str
    chunks: list[PolicyChunk] = Field(default_factory=list, max_length=1000)


class PolicyQueryRequest(StrictModel):
    question: str = Field(min_length=4, max_length=1000)
    documents: list[PolicyDocument] = Field(default_factory=list, max_length=500)

    @model_validator(mode="after")
    def enforce_retrieval_limits(self):
        chunks = [chunk for document in self.documents for chunk in document.chunks]
        if len(chunks) > 500:
            raise ValueError("Policy query supports at most 500 indexed chunks per request")
        if sum(len(chunk.text) for chunk in chunks) > 2_000_000:
            raise ValueError("Policy excerpts exceed the request size limit")
        return self


class PolicyCitation(StrictModel):
    document_id: str
    title: str
    chunk_id: str
    page: int | None = None
    section: str | None = None


class PolicyAnswer(StrictModel):
    answer: str = Field(max_length=6000)
    citations: list[PolicyCitation] = Field(default_factory=list, max_length=10)
    supported_by_documents: bool


class GeneratedPolicyAnswer(StrictModel):
    answer: str = Field(max_length=6000)
    source_ids: list[str] = Field(default_factory=list, max_length=10)
    supported_by_documents: bool
