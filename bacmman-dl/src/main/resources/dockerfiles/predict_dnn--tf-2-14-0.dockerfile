FROM tensorflow/tensorflow:2.14.0-gpu
RUN apt-get -y clean && apt-get -y update
RUN apt-get -y install wget
RUN pip install --upgrade h5py==3.11.0

RUN wget https://gist.githubusercontent.com/jeanollion/7b156bdbd7769f7a0a64b6774550ff4d/raw/predict_dev.py -O predict.py
RUN chmod a+r predict.py

CMD ["python", "predict.py"]