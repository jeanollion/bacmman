FROM jeanollion/training_dnn:tf-2.14.0
RUN pip install --upgrade h5py==3.11.0

RUN wget https://gist.githubusercontent.com/jeanollion/7b156bdbd7769f7a0a64b6774550ff4d/raw/predict.py -O predict.py
RUN chmod a+r predict.py

# Start an interactive Python shell
CMD ["python", "-i", "predict.py"]