from app.rag.policy_service import retrieve
from app.schemas.policies import PolicyChunk, PolicyDocument, PolicyQueryRequest


def test_policy_retrieval_returns_only_relevant_chunks():
    request = PolicyQueryRequest(question="How many laboratory periods are required?", documents=[
        PolicyDocument(id="lab", title="Lab policy", chunks=[
            PolicyChunk(id="p1", text="Laboratory sessions must use two contiguous teaching periods."),
            PolicyChunk(id="p2", text="The academic calendar lists examination holidays."),
        ])
    ])
    hits = retrieve(request.question, request)
    assert len(hits) == 1
    assert hits[0][1].id == "p1"
