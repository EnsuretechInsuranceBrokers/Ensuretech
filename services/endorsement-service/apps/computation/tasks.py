from celery import shared_task


@shared_task(name="apps.computation.tasks.start_premium_calculation")
def start_premium_calculation(job_id: str):
    """Implemented in the computation slice."""
    return None
