"""Redis list consumer that dispatches Celery tasks."""
import time
import redis
from config import REDIS_HOST, REDIS_PORT, TASK_QUEUE_KEY, RAG_TASK_QUEUE_KEY
from tasks import process_submission, process_rag_document


def main():
    """Poll Redis list and dispatch Celery tasks.

    Listens on both the grading queue and the RAG queue using a single
    ``blpop`` call with multiple keys.  Dispatches to the appropriate
    Celery task based on which key was popped.
    """
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=0, decode_responses=True)
    queues = [TASK_QUEUE_KEY, RAG_TASK_QUEUE_KEY]
    print(f"Queue consumer started, listening on {queues}...", flush=True)

    while True:
        # Blocking pop with 5s timeout — checks both queues
        result = r.blpop(queues, timeout=5)
        if result:
            queue_key, message = result
            if queue_key == RAG_TASK_QUEUE_KEY:
                print(f"Dispatching RAG task: {message[:100]}...", flush=True)
                process_rag_document.delay(message)
            else:
                print(f"Dispatching grading task: {message[:100]}...", flush=True)
                process_submission.delay(message)


if __name__ == "__main__":
    main()
