FROM jeanollion/training_dnn:tf-2.18.0
RUN pip install tf-keras==2.18.0
RUN wget https://gist.githubusercontent.com/jeanollion/7b156bdbd7769f7a0a64b6774550ff4d/raw/predict_dev.py -O predict.py
RUN chmod a+r predict.py
ENV TF_USE_LEGACY_KERAS=1
CMD ["python", "predict.py"]